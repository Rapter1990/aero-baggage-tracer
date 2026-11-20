package com.aerotrace.search.reindex;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.MgetResponse;
import co.elastic.clients.elasticsearch.core.mget.MultiGetResponseItem;
import com.aerotrace.common.error.ApiException;
import com.aerotrace.common.error.ErrorCode;
import com.aerotrace.common.event.CaseChangedPayload;
import com.aerotrace.common.event.LegSnapshot;
import com.aerotrace.common.export.BagExport;
import com.aerotrace.common.export.ExportPage;
import com.aerotrace.search.config.SearchProperties;
import com.aerotrace.search.constant.MetricNames;
import com.aerotrace.search.elastic.Elastic;
import com.aerotrace.search.elastic.IndexAdmin;
import com.aerotrace.search.elastic.IndexFamily;
import com.aerotrace.search.indexer.EventIndexer;
import com.aerotrace.search.indexer.JourneyChange;
import com.aerotrace.search.indexer.ReadModelWriter;
import com.aerotrace.search.indexer.ReadModelWriter.CaseWrite;
import com.aerotrace.search.indexer.ReadModelWriter.ItemResult;
import com.aerotrace.search.reindex.ReindexJob.Scope;
import com.aerotrace.search.reindex.ReindexJob.Status;
import com.aerotrace.search.reindex.ReindexJobStore.KeyBinding;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Service;

/**
 * Rebuilds the read models without downtime (ADR 0004, decision 6):
 *
 * <ol>
 *   <li>create the next index of each family from its template and add it to the {@code -live}
 *       alias, so live events now reach both the old and the new index; wait until writes already
 *       in flight have landed;
 *   <li>load the owners' exports page by page into the new indexes only, with the same idempotent
 *       writes as the live indexer (external versions, guarded scripts), and for journeys replay
 *       the risk topic; live and snapshot writes may overlap in any order;
 *   <li>check every page with a realtime multi-get: each exported case is in the new index at its
 *       version or a newer one, each exported bag has its journey; then compare the counts and
 *       record id checksums;
 *   <li>only then move every rebuilt {@code -read} alias to its new index in one atomic {@code
 *       _aliases} call and take the old ones out of {@code -live}; the janitor deletes them after
 *       the point-in-time grace.
 * </ol>
 *
 * If anything fails, the new indexes are discarded and searches never noticed. A job holds the
 * rebuild lock for its whole run; when it cannot renew it, or finds it taken over, it stops, never
 * swaps and fails. A job that takes over the lock of a job whose instance died marks that job
 * FAILED and discards the indexes it left in the live aliases. The source is only read: {@code
 * GET}s of the export endpoints and a group-less replay of a topic.
 */
@Service
public class ReindexService implements DisposableBean {

  private static final Logger LOG = LoggerFactory.getLogger(ReindexService.class);

  /** The error of a job whose instance stopped renewing the lock, set by the job that took over. */
  static final String ABANDONED = "abandoned: its instance stopped renewing the rebuild lock";

  static final String LOCK_LOST = "rebuild lock lost: another job took it over";
  static final String LOCK_NOT_RENEWED = "rebuild lock not renewed";

  private final SourceExportClient source;
  private final RiskTopicReplay riskReplay;
  private final ReadModelWriter writer;
  private final EventIndexer indexer;
  private final IndexAdmin admin;
  private final ReindexJobStore store;
  private final ElasticsearchClient client;
  private final MeterRegistry meters;
  private final SearchProperties properties;
  private final Clock clock;
  private final ExecutorService jobs = Executors.newVirtualThreadPerTaskExecutor();
  private final ScheduledExecutorService heartbeats =
      Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().factory());

  public ReindexService(
      SourceExportClient source,
      RiskTopicReplay riskReplay,
      ReadModelWriter writer,
      EventIndexer indexer,
      IndexAdmin admin,
      ReindexJobStore store,
      ElasticsearchClient client,
      MeterRegistry meters,
      SearchProperties properties,
      Clock clock) {
    this.source = source;
    this.riskReplay = riskReplay;
    this.writer = writer;
    this.indexer = indexer;
    this.admin = admin;
    this.store = store;
    this.client = client;
    this.meters = meters;
    this.properties = properties;
    this.clock = clock;
  }

  /**
   * Starts a rebuild, or returns the job an earlier request with the same key started.
   *
   * @throws ApiException {@code REINDEX_IN_PROGRESS} while another job holds the lock; {@code
   *     IDEMPOTENCY_KEY_REUSED} when the key was used for another scope or by another caller
   */
  public ReindexJob start(Scope scope, String idempotencyKey, String requestedBy) {
    return Elastic.call(
        () -> {
          Optional<ReindexJob> earlier = jobOfKey(idempotencyKey, scope, requestedBy);
          if (earlier.isPresent()) {
            return earlier.get();
          }
          String jobId = UUID.randomUUID().toString();
          ReindexJobStore.Lock lock = store.lock(jobId, properties.reindex().lockLease());
          if (!lock.acquired()) {
            // The same request may have just been started by a parallel call.
            return jobOfKey(idempotencyKey, scope, requestedBy)
                .orElseThrow(
                    () ->
                        new ApiException(
                            ErrorCode.REINDEX_IN_PROGRESS,
                            "A rebuild is already running. Try again when it has finished."));
          }
          ReindexJob job =
              new ReindexJob(
                  jobId, Status.RUNNING, scope, requestedBy, clock.instant(), null, null, Map.of());
          store.save(job);
          if (!store.bindKey(idempotencyKey, jobId, scope, requestedBy)) {
            store.save(finished(job, Status.FAILED, "superseded by a parallel request", Map.of()));
            store.unlock(jobId);
            return jobOfKey(idempotencyKey, scope, requestedBy).orElseThrow();
          }
          jobs.submit(() -> run(job, lock.abandonedJob()));
          return job;
        });
  }

  private Optional<ReindexJob> jobOfKey(String key, Scope scope, String requestedBy)
      throws IOException {
    Optional<KeyBinding> binding = store.keyBinding(key);
    if (binding.isEmpty()) {
      return Optional.empty();
    }
    if (binding.get().scope() != scope
        || !Objects.equals(binding.get().requestedBy(), requestedBy)) {
      throw new ApiException(
          ErrorCode.IDEMPOTENCY_KEY_REUSED,
          "This Idempotency-Key was used for another rebuild request. Use a new key.");
    }
    return store.find(binding.get().jobId());
  }

  public Optional<ReindexJob> find(String jobId) {
    return Elastic.call(() -> store.find(jobId));
  }

  private ReindexJob finished(
      ReindexJob job, Status status, String error, Map<String, Object> results) {
    return new ReindexJob(
        job.jobId(),
        status,
        job.scope(),
        job.requestedBy(),
        job.startedAt(),
        clock.instant(),
        error,
        results);
  }

  /** A check of the rebuild failed: the new index is not complete or does not match the source. */
  static final class RebuildCheckFailed extends RuntimeException {
    RebuildCheckFailed(String message) {
      super(message);
    }
  }

  /** The job was stopped: its lock is lost or could not be renewed. */
  static final class JobAborted extends RuntimeException {
    JobAborted(String message) {
      super(message);
    }
  }

  /** A running job: its deadline, and why it must stop, once something says so. */
  final class Running {
    final ReindexJob job;
    final Instant deadline;
    final Thread worker = Thread.currentThread();
    volatile String abortReason;

    Running(ReindexJob job) {
      this.job = job;
      this.deadline = job.startedAt().plus(properties.reindex().maxDuration());
    }

    /** Stops the job: it fails at its next step (and a blocking call is interrupted). */
    void abort(String reason) {
      if (abortReason == null) {
        abortReason = reason;
        LOG.warn("Rebuild {} aborted: {}", job.jobId(), reason);
        worker.interrupt();
      }
    }

    /** Called between steps: fails the job once it was aborted or ran out of time. */
    void check() {
      if (abortReason != null) {
        throw new JobAborted(abortReason);
      }
      if (clock.instant().isAfter(deadline)) {
        throw new RebuildCheckFailed("the rebuild took longer than its maximum duration");
      }
    }
  }

  void run(ReindexJob job, String abandonedJob) {
    Running running = new Running(job);
    Map<String, Object> results = new LinkedHashMap<>();
    Map<IndexFamily, String> created = new LinkedHashMap<>();
    ScheduledFuture<?> heartbeat =
        heartbeats.scheduleAtFixedRate(
            () -> heartbeat(running),
            properties.reindex().heartbeat().toMillis(),
            properties.reindex().heartbeat().toMillis(),
            TimeUnit.MILLISECONDS);
    String result = "failed";
    boolean interrupted = false;
    try {
      if (abandonedJob != null) {
        cleanUpAbandoned(abandonedJob);
      }
      if (job.scope().cases()) {
        created.put(IndexFamily.CASES, admin.createNext(IndexFamily.CASES));
      }
      if (job.scope().journeys()) {
        created.put(IndexFamily.JOURNEYS, admin.createNext(IndexFamily.JOURNEYS));
      }
      for (Map.Entry<IndexFamily, String> next : created.entrySet()) {
        admin.addToLive(next.getKey(), next.getValue());
      }
      // Writes resolved against the old alias before this point land before the export is read.
      Thread.sleep(properties.index().liveSettle().toMillis());
      running.check();

      String casesIndex = created.get(IndexFamily.CASES);
      String journeys = created.get(IndexFamily.JOURNEYS);
      Load cases = loadCases(running, casesIndex, journeys);
      Load bags = null;
      long risks = 0;
      if (journeys != null) {
        bags = loadBags(running, journeys);
        risks =
            riskReplay.replay(
                batch -> {
                  running.check();
                  if (indexer.applyRisks(batch, List.of(journeys)).stream()
                      .anyMatch(ItemResult::rejected)) {
                    throw new RebuildCheckFailed(
                        "journeys: the index refused replayed risk events");
                  }
                });
      }

      if (casesIndex != null) {
        results.put("cases", cases.check(IndexFamily.CASES, casesIndex, "cases", Map.of()));
      }
      if (journeys != null) {
        results.put(
            "journeys",
            bags.check(
                IndexFamily.JOURNEYS, journeys, "journeys", Map.of("riskEventsReplayed", risks)));
      }

      running.check();
      // A job that lost its lock never swaps, even if its heartbeat has not noticed yet.
      if (!store.holds(job.jobId())) {
        throw new JobAborted(LOCK_LOST);
      }
      admin.swapRead(created);
      store.save(finished(job, Status.SUCCEEDED, null, results));
      result = "succeeded";
      LOG.info("Rebuild {} succeeded: {}", job.jobId(), created.values());
    } catch (Exception e) {
      // Cleared so the clean-up below can still talk to Elasticsearch; set again at the end.
      interrupted = Thread.interrupted() || e instanceof InterruptedException;
      String reason = reason(running, e);
      LOG.warn("Rebuild {} {}", job.jobId(), reason);
      for (Map.Entry<IndexFamily, String> next : created.entrySet()) {
        try {
          admin.discard(next.getKey(), next.getValue());
        } catch (Exception cleanup) {
          LOG.warn("Index {} not discarded; the janitor removes it", next.getValue());
        }
      }
      saveQuietly(finished(job, Status.FAILED, reason, results));
    } finally {
      heartbeat.cancel(false);
      try {
        store.unlock(job.jobId());
      } catch (Exception e) {
        LOG.warn("Rebuild lock not released; its lease expires");
      }
      meters.counter(MetricNames.REINDEX_RUNS, MetricNames.TAG_RESULT, result).increment();
      if (interrupted) {
        Thread.currentThread().interrupt();
      }
    }
  }

  /** The job's error: a fixed text, never an exception message from a source or a client. */
  private static String reason(Running running, Exception e) {
    if (running.abortReason != null) {
      return running.abortReason;
    }
    if (e instanceof RebuildCheckFailed
        || e instanceof JobAborted
        || e instanceof SourceExportException) {
      return e.getMessage();
    }
    return "failed: " + e.getClass().getSimpleName();
  }

  /**
   * Renews the lock. A job that cannot renew it stops at once: its lease may run out, and another
   * instance may then take the lock and run a rebuild of its own next to it.
   */
  private void heartbeat(Running running) {
    try {
      if (!store.renew(running.job.jobId(), properties.reindex().lockLease())) {
        running.abort(LOCK_LOST);
      }
    } catch (Exception e) {
      running.abort(LOCK_NOT_RENEWED + ": " + e.getClass().getSimpleName());
    }
  }

  /**
   * The previous holder of the lock died mid-run: its job stays RUNNING and its half-built index
   * stays in the live alias (written by every live event, read by nobody) unless the job that took
   * the lock over cleans up after it.
   */
  private void cleanUpAbandoned(String abandonedJob) throws IOException {
    Optional<ReindexJob> previous = store.find(abandonedJob);
    if (previous.isPresent() && previous.get().status() == Status.RUNNING) {
      store.save(finished(previous.get(), Status.FAILED, ABANDONED, previous.get().results()));
    }
    for (IndexFamily family : IndexFamily.values()) {
      List<String> discarded = admin.discardUnread(family);
      if (!discarded.isEmpty()) {
        LOG.warn("Discarded {} left by abandoned rebuild {}", discarded, abandonedJob);
      }
    }
  }

  private void saveQuietly(ReindexJob job) {
    try {
      store.save(job);
    } catch (Exception e) {
      LOG.warn("Rebuild job {} not saved", job.jobId());
    }
  }

  // ------------------------------------------------------------------ checks

  /**
   * What was loaded into a new index: counts, order-independent id checksums and the exported
   * records that are missing or newer in the index.
   */
  final class Load {
    long sourceCount;
    long missing;
    long newer;
    long sourceChecksum;
    long indexChecksum;

    Map<String, Object> check(
        IndexFamily family, String index, String name, Map<String, Object> extra)
        throws IOException {
      admin.refresh(index);
      long indexed = client.count(c -> c.index(index)).count();
      Map<String, Object> summary = new LinkedHashMap<>();
      summary.put("index", index);
      summary.put("previousIndex", admin.readIndex(family));
      summary.put("sourceCount", sourceCount);
      summary.put("indexedCount", indexed);
      summary.put("missing", missing);
      if (family == IndexFamily.CASES) {
        summary.put("newerThanSource", newer);
      }
      summary.put("sourceChecksum", HexFormat.of().toHexDigits(sourceChecksum));
      summary.put("indexChecksum", HexFormat.of().toHexDigits(indexChecksum));
      summary.putAll(extra);
      if (missing > 0) {
        throw new RebuildCheckFailed(name + ": exported records missing from the new index");
      }
      // Live events may add documents the export did not have yet, never fewer.
      if (indexed < sourceCount) {
        throw new RebuildCheckFailed(name + ": fewer documents than records in the source");
      }
      return summary;
    }
  }

  /**
   * Each exported record must be in the new index (a realtime multi-get, so no refresh is needed):
   * {@code version} gives the version the source exported, or -1 when the index's version is not
   * compared (journeys have none). The checksums sum a fingerprint of id and version (cases) or of
   * the id alone (journeys).
   */
  @SuppressWarnings({"rawtypes"})
  private <T> void verify(
      String index, List<T> items, Function<T, String> id, Function<T, Long> version, Load load)
      throws IOException {
    if (items.isEmpty()) {
      return;
    }
    MgetResponse<Map> found =
        client.mget(
            m -> m.index(index).ids(items.stream().map(id).toList()).source(s -> s.fetch(false)),
            Map.class);
    for (int i = 0; i < items.size(); i++) {
      T expected = items.get(i);
      long exported = version.apply(expected);
      MultiGetResponseItem<Map> item = found.docs().get(i);
      load.sourceCount++;
      load.sourceChecksum += fingerprint(id.apply(expected), Math.max(exported, 0));
      if (!item.isResult() || !item.result().found()) {
        load.missing++;
        continue;
      }
      if (exported < 0) {
        load.indexChecksum += fingerprint(id.apply(expected), 0);
        continue;
      }
      long stored = item.result().version();
      load.indexChecksum += fingerprint(id.apply(expected), stored);
      if (stored > exported) {
        load.newer++;
      }
    }
  }

  /** FNV-1a 64 of "id:version"; summed, so the order of the pages does not matter. */
  static long fingerprint(String id, long version) {
    long hash = 0xcbf29ce484222325L;
    for (byte b : (id + ":" + version).getBytes(StandardCharsets.UTF_8)) {
      hash ^= b & 0xff;
      hash *= 0x100000001b3L;
    }
    return hash;
  }

  /**
   * Guards a keyset-paged export against a source that misbehaves: the next key must move on, an
   * empty page must be the last, and a family may take at most {@code max-pages} pages.
   */
  private String nextAfter(String family, String after, ExportPage<?> page, int pages) {
    String next = page.nextAfter();
    if (next != null && (next.equals(after) || page.items().isEmpty())) {
      throw new RebuildCheckFailed(family + ": the export did not advance");
    }
    if (next != null && pages >= properties.reindex().maxPages()) {
      throw new RebuildCheckFailed(family + ": the export has more pages than the maximum");
    }
    return next;
  }

  private static Instant latest(Instant a, Instant b) {
    if (a == null) {
      return b;
    }
    return b == null || a.isAfter(b) ? a : b;
  }

  // ------------------------------------------------------------------ cases

  private Load loadCases(Running running, String casesIndex, String journeysIndex)
      throws IOException {
    Load load = new Load();
    if (casesIndex == null && journeysIndex == null) {
      return load;
    }
    String after = null;
    int pages = 0;
    do {
      running.check();
      ExportPage<CaseChangedPayload> page = source.cases(after, properties.reindex().pageSize());
      pages++;
      List<CaseChangedPayload> items = page.items();
      if (casesIndex != null && !items.isEmpty()) {
        List<CaseWrite> writes = items.stream().map(c -> new CaseWrite(c, null)).toList();
        List<ItemResult> written = writer.writeCases(writes, List.of(casesIndex));
        if (written.stream().anyMatch(ItemResult::rejected)) {
          throw new RebuildCheckFailed("cases: the index refused exported cases");
        }
        verify(casesIndex, items, CaseChangedPayload::caseId, CaseChangedPayload::version, load);
      }
      if (journeysIndex != null && !items.isEmpty()) {
        List<JourneyChange> changes = new ArrayList<>();
        for (CaseChangedPayload c : items) {
          // The case's own latest time: a rebuilt journey's updatedAt is deterministic.
          changes.add(EventIndexer.caseOnJourney(c, latest(c.openedAt(), c.resolvedAt())));
        }
        if (writer.applyJourneys(changes, List.of(journeysIndex)).stream()
            .anyMatch(ItemResult::rejected)) {
          throw new RebuildCheckFailed("journeys: the index refused exported cases");
        }
      }
      after = nextAfter("cases", after, page, pages);
    } while (after != null);
    return load;
  }

  // ------------------------------------------------------------------ bags

  /**
   * Loads the bag export. A rebuilt journey's {@code updatedAt} is the latest of its legs'
   * departures, its latest scan, its cases' {@code openedAt}/{@code resolvedAt} and its replayed
   * risk events: all owner data, so two rebuilds give the same value. (Live, it is the time of the
   * latest event that changed the journey; the export has no event times.)
   */
  private Load loadBags(Running running, String journeysIndex) throws IOException {
    Load load = new Load();
    String after = null;
    int pages = 0;
    do {
      running.check();
      ExportPage<BagExport> page = source.bags(after, properties.reindex().pageSize());
      pages++;
      Map<String, LegSnapshot> legs = new LinkedHashMap<>();
      Map<String, String> bagOfLeg = new LinkedHashMap<>();
      List<JourneyChange> changes = new ArrayList<>();
      for (BagExport bag : page.items()) {
        List<LegSnapshot> itinerary = bag.legs() == null ? List.of() : bag.legs();
        Instant departure = null;
        for (LegSnapshot leg : itinerary) {
          legs.put(leg.legId(), leg);
          bagOfLeg.put(leg.legId(), bag.bagTag());
          departure = latest(departure, leg.departureAt());
        }
        changes.add(JourneyChange.itinerary(bag.bagTag(), itinerary, departure));
        if (bag.latestScan() != null) {
          BagExport.LatestScan scan = bag.latestScan();
          changes.add(
              JourneyChange.scan(
                  bag.bagTag(),
                  scan.airport(),
                  scan.checkpoint(),
                  scan.deviceSequence(),
                  scan.occurredAt(),
                  scan.legId(),
                  null,
                  null));
        }
      }
      if (!writer.writeLegs(legs, bagOfLeg).isEmpty()) {
        throw new RebuildCheckFailed("journeys: the index refused exported legs");
      }
      List<ItemResult> written = writer.applyJourneys(changes, List.of(journeysIndex));
      if (written.stream().anyMatch(ItemResult::rejected)) {
        throw new RebuildCheckFailed("journeys: the index refused exported bags");
      }
      verify(journeysIndex, page.items(), BagExport::bagTag, bag -> -1L, load);
      after = nextAfter("journeys", after, page, pages);
    } while (after != null);
    return load;
  }

  @Override
  public void destroy() {
    jobs.shutdownNow();
    heartbeats.shutdownNow();
  }
}
