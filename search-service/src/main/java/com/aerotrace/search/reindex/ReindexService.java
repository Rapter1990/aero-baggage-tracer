package com.aerotrace.search.reindex;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.MgetResponse;
import co.elastic.clients.elasticsearch.core.mget.MultiGetResponseItem;
import com.aerotrace.common.error.ApiException;
import com.aerotrace.common.error.ErrorCode;
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
import com.aerotrace.search.indexer.model.CaseChange;
import com.aerotrace.search.indexer.model.LegSnapshot;
import com.aerotrace.search.reindex.ReindexJob.Scope;
import com.aerotrace.search.reindex.ReindexJob.Status;
import com.aerotrace.search.reindex.SourceExportClient.BagExport;
import com.aerotrace.search.reindex.SourceExportClient.ExportPage;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
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
 *   <li>check every page: each exported case is in the new index at its version or a newer one;
 *       then compare the counts;
 *   <li>only then move {@code -read} to the new index in one atomic {@code _aliases} call and take
 *       the old one out of {@code -live}; the janitor deletes it after the point-in-time grace.
 * </ol>
 *
 * If anything fails, the new indexes are discarded and searches never noticed. The source is only
 * read: {@code GET}s of the export endpoints and a group-less replay of a topic.
 */
@Service
public class ReindexService implements DisposableBean {

  private static final Logger LOG = LoggerFactory.getLogger(ReindexService.class);

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
   * @throws ApiException {@code REINDEX_IN_PROGRESS} while another job holds the lock
   */
  public ReindexJob start(Scope scope, String idempotencyKey, String requestedBy) {
    return Elastic.call(
        () -> {
          Optional<ReindexJob> earlier = jobOfKey(idempotencyKey);
          if (earlier.isPresent()) {
            return earlier.get();
          }
          String jobId = UUID.randomUUID().toString();
          if (!store.lock(jobId, properties.reindex().lockLease())) {
            // The same request may have just been started by a parallel call.
            return jobOfKey(idempotencyKey)
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
          if (!store.bindKey(idempotencyKey, jobId)) {
            store.save(finished(job, Status.FAILED, "superseded by a parallel request", Map.of()));
            store.unlock(jobId);
            return jobOfKey(idempotencyKey).orElseThrow();
          }
          jobs.submit(() -> run(job));
          return job;
        });
  }

  private Optional<ReindexJob> jobOfKey(String key) throws IOException {
    Optional<String> jobId = store.jobOfKey(key);
    return jobId.isPresent() ? store.find(jobId.get()) : Optional.empty();
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

  void run(ReindexJob job) {
    Map<String, Object> results = new LinkedHashMap<>();
    Map<IndexFamily, String> created = new LinkedHashMap<>();
    ScheduledFuture<?> heartbeat =
        heartbeats.scheduleAtFixedRate(
            () -> renew(job.jobId()),
            properties.reindex().heartbeat().toMillis(),
            properties.reindex().heartbeat().toMillis(),
            TimeUnit.MILLISECONDS);
    String result = "failed";
    try {
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

      CaseLoad cases = loadCases(created.get(IndexFamily.CASES), created.get(IndexFamily.JOURNEYS));
      long bags = 0;
      long risks = 0;
      String journeys = created.get(IndexFamily.JOURNEYS);
      if (journeys != null) {
        bags = loadBags(journeys);
        risks = riskReplay.replay(batch -> indexer.applyRisks(batch, List.of(journeys)));
      }

      String casesIndex = created.get(IndexFamily.CASES);
      if (casesIndex != null) {
        results.put("cases", cases.check(casesIndex));
      }
      if (journeys != null) {
        admin.refresh(journeys);
        long indexed = client.count(c -> c.index(journeys)).count();
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("index", journeys);
        summary.put("previousIndex", admin.readIndex(IndexFamily.JOURNEYS));
        summary.put("sourceCount", bags);
        summary.put("indexedCount", indexed);
        summary.put("riskEventsReplayed", risks);
        results.put("journeys", summary);
        if (indexed < bags) {
          throw new RebuildCheckFailed("journeys: fewer documents than bags in the source");
        }
      }

      for (Map.Entry<IndexFamily, String> next : created.entrySet()) {
        admin.swapRead(next.getKey(), next.getValue());
      }
      store.save(finished(job, Status.SUCCEEDED, null, results));
      result = "succeeded";
      LOG.info("Rebuild {} succeeded: {}", job.jobId(), created.values());
    } catch (Exception e) {
      if (e instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      String reason =
          e instanceof RebuildCheckFailed
              ? e.getMessage()
              : "failed: " + e.getClass().getSimpleName();
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
    }
  }

  private void renew(String jobId) {
    try {
      store.renew(jobId, properties.reindex().lockLease());
    } catch (Exception e) {
      LOG.warn("Rebuild lock not renewed: {}", e.getClass().getSimpleName());
    }
  }

  private void saveQuietly(ReindexJob job) {
    try {
      store.save(job);
    } catch (Exception e) {
      LOG.warn("Rebuild job {} not saved", job.jobId());
    }
  }

  // ------------------------------------------------------------------ cases

  /** The case export as loaded: counts, checksums and missing or newer documents. */
  final class CaseLoad {
    long sourceCount;
    long missing;
    long newer;
    long sourceChecksum;
    long indexChecksum;

    Map<String, Object> check(String index) throws IOException {
      admin.refresh(index);
      long indexed = client.count(c -> c.index(index)).count();
      Map<String, Object> summary = new LinkedHashMap<>();
      summary.put("index", index);
      summary.put("previousIndex", admin.readIndex(IndexFamily.CASES));
      summary.put("sourceCount", sourceCount);
      summary.put("indexedCount", indexed);
      summary.put("missing", missing);
      summary.put("newerThanSource", newer);
      summary.put("sourceChecksum", HexFormat.of().toHexDigits(sourceChecksum));
      summary.put("indexChecksum", HexFormat.of().toHexDigits(indexChecksum));
      if (missing > 0) {
        throw new RebuildCheckFailed("cases: exported cases missing from the new index");
      }
      // Live events may add cases the export did not have yet, never fewer.
      if (indexed < sourceCount) {
        throw new RebuildCheckFailed("cases: fewer documents than cases in the source");
      }
      return summary;
    }
  }

  private CaseLoad loadCases(String casesIndex, String journeysIndex) throws IOException {
    CaseLoad load = new CaseLoad();
    if (casesIndex == null && journeysIndex == null) {
      return load;
    }
    String after = null;
    do {
      ExportPage<CaseChange> page = source.cases(after, properties.reindex().pageSize());
      List<CaseChange> items = page.items();
      if (casesIndex != null && !items.isEmpty()) {
        List<CaseWrite> writes = items.stream().map(c -> new CaseWrite(c, null)).toList();
        List<ItemResult> written = writer.writeCases(writes, List.of(casesIndex));
        if (written.stream().anyMatch(ItemResult::rejected)) {
          throw new RebuildCheckFailed("cases: the index refused exported cases");
        }
        verify(casesIndex, items, load);
      }
      if (journeysIndex != null && !items.isEmpty()) {
        List<JourneyChange> changes = new ArrayList<>();
        for (CaseChange c : items) {
          changes.add(EventIndexer.caseOnJourney(c, null));
        }
        writer.applyJourneys(changes, List.of(journeysIndex));
      }
      after = page.nextAfter();
    } while (after != null);
    return load;
  }

  /** Each exported case must be in the new index at its version or newer (a live event). */
  @SuppressWarnings({"rawtypes"})
  private void verify(String index, List<CaseChange> items, CaseLoad load) throws IOException {
    MgetResponse<Map> found =
        client.mget(
            m ->
                m.index(index)
                    .ids(items.stream().map(CaseChange::caseId).toList())
                    .source(s -> s.fetch(false)),
            Map.class);
    for (int i = 0; i < items.size(); i++) {
      CaseChange expected = items.get(i);
      MultiGetResponseItem<Map> item = found.docs().get(i);
      load.sourceCount++;
      load.sourceChecksum += fingerprint(expected.caseId(), expected.version());
      if (!item.isResult() || !item.result().found()) {
        load.missing++;
        continue;
      }
      long version = item.result().version();
      load.indexChecksum += fingerprint(expected.caseId(), version);
      if (version > expected.version()) {
        load.newer++;
      }
    }
  }

  /** FNV-1a 64 of "id:version"; summed, so the order of the pages does not matter. */
  static long fingerprint(String id, long version) {
    long hash = 0xcbf29ce484222325L;
    for (byte b : (id + ":" + version).getBytes(java.nio.charset.StandardCharsets.UTF_8)) {
      hash ^= b & 0xff;
      hash *= 0x100000001b3L;
    }
    return hash;
  }

  // ------------------------------------------------------------------ bags

  private long loadBags(String journeysIndex) throws IOException {
    long count = 0;
    String after = null;
    do {
      ExportPage<BagExport> page = source.bags(after, properties.reindex().pageSize());
      Map<String, LegSnapshot> legs = new LinkedHashMap<>();
      Map<String, String> bagOfLeg = new LinkedHashMap<>();
      List<JourneyChange> changes = new ArrayList<>();
      for (BagExport bag : page.items()) {
        List<LegSnapshot> itinerary = bag.legs() == null ? List.of() : bag.legs();
        for (LegSnapshot leg : itinerary) {
          legs.put(leg.legId(), leg);
          bagOfLeg.put(leg.legId(), bag.bagTag());
        }
        changes.add(JourneyChange.itinerary(bag.bagTag(), itinerary, null));
        if (bag.latestScan() != null) {
          SourceExportClient.LatestScan scan = bag.latestScan();
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
        count++;
      }
      writer.writeLegs(legs, bagOfLeg);
      List<ItemResult> written = writer.applyJourneys(changes, List.of(journeysIndex));
      if (written.stream().anyMatch(ItemResult::rejected)) {
        throw new RebuildCheckFailed("journeys: the index refused exported bags");
      }
      after = page.nextAfter();
    } while (after != null);
    return count;
  }

  @Override
  public void destroy() {
    jobs.shutdownNow();
    heartbeats.shutdownNow();
  }
}
