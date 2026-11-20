package com.aerotrace.search.reindex;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch.core.GetResponse;
import com.aerotrace.search.config.SearchProperties;
import com.aerotrace.search.document.JobDocument;
import com.aerotrace.search.elastic.Elastic;
import com.aerotrace.search.elastic.SupportIndex;
import com.aerotrace.search.reindex.ReindexJob.Scope;
import com.aerotrace.search.reindex.ReindexJob.Status;
import com.aerotrace.search.repository.ReindexJobRepository;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Jobs, the rebuild lock and idempotency keys, kept in Elasticsearch itself (the service has no
 * database), so they hold across instances:
 *
 * <ul>
 *   <li>{@code lock}: created with {@code op_type=create}, so only one instance gets it; it has a
 *       lease that the running job renews. Every change after the creation (renewal, takeover of an
 *       expired lease, release) is conditional on the sequence number just read and on the job id
 *       in it, so a job never extends, or releases, a lock another job holds;
 *   <li>{@code key:<sha-256 of the Idempotency-Key>}: created once and names the job, the scope and
 *       the caller, so a repeated request gets the same job and a reused key for another request is
 *       refused; keys expire after {@code key-ttl};
 *   <li>{@code job:<jobId>}: the job itself.
 * </ul>
 *
 * The job documents are read and written through {@link ReindexJobRepository}; the lock and the
 * keys need {@code op_type=create} and conditional writes, which a repository does not offer, so
 * they use the client.
 */
@Component
public class ReindexJobStore {

  private static final Logger LOG = LoggerFactory.getLogger(ReindexJobStore.class);
  private static final String LOCK = "lock";
  private static final String KEY = "key:";
  private static final String JOB = "job:";

  /**
   * The outcome of taking the lock.
   *
   * @param acquired whether this job holds it now
   * @param abandonedJob the job whose expired lease was taken over (its instance stopped renewing
   *     it), or null
   */
  public record Lock(boolean acquired, String abandonedJob) {
    static final Lock REFUSED = new Lock(false, null);
  }

  /** What an idempotency key was first used for. */
  public record KeyBinding(String jobId, Scope scope, String requestedBy, Instant createdAt) {}

  private final ElasticsearchClient client;
  private final ReindexJobRepository jobs;
  private final Clock clock;
  private final Duration keyTtl;

  public ReindexJobStore(
      ElasticsearchClient client,
      ReindexJobRepository jobs,
      Clock clock,
      SearchProperties properties) {
    this.client = client;
    this.jobs = jobs;
    this.clock = clock;
    this.keyTtl = properties.reindex().keyTtl();
  }

  private static String index() {
    return SupportIndex.JOBS.index();
  }

  /** Takes the lock for a job; refused while another job holds a live lease. */
  @SuppressWarnings("rawtypes")
  public Lock lock(String jobId, Duration lease) throws IOException {
    Map<String, Object> doc = lockDoc(jobId, lease);
    try {
      client.create(c -> c.index(index()).id(LOCK).document(doc));
      return new Lock(true, null);
    } catch (ElasticsearchException e) {
      if (e.status() != 409) {
        throw e;
      }
    }
    GetResponse<Map> current = client.get(g -> g.index(index()).id(LOCK), Map.class);
    if (!current.found()) {
      return lock(jobId, lease);
    }
    Instant until = Elastic.instant(current.source().get("leaseUntil"));
    if (until != null && until.isAfter(clock.instant())) {
      return Lock.REFUSED;
    }
    try {
      client.index(
          i ->
              i.index(index())
                  .id(LOCK)
                  .ifSeqNo(current.seqNo())
                  .ifPrimaryTerm(current.primaryTerm())
                  .document(doc));
      return new Lock(true, (String) current.source().get("jobId"));
    } catch (ElasticsearchException e) {
      if (e.status() == 409) {
        return Lock.REFUSED;
      }
      throw e;
    }
  }

  /**
   * Extends the lease of the job's lock, only if the job still holds it.
   *
   * @return false when the lock is gone or another job holds it now (it was taken over)
   */
  @SuppressWarnings("rawtypes")
  public boolean renew(String jobId, Duration lease) throws IOException {
    GetResponse<Map> current = client.get(g -> g.index(index()).id(LOCK), Map.class);
    if (!current.found() || !jobId.equals(current.source().get("jobId"))) {
      return false;
    }
    try {
      client.index(
          i ->
              i.index(index())
                  .id(LOCK)
                  .ifSeqNo(current.seqNo())
                  .ifPrimaryTerm(current.primaryTerm())
                  .document(lockDoc(jobId, lease)));
      return true;
    } catch (ElasticsearchException e) {
      if (e.status() == 409) {
        return false;
      }
      throw e;
    }
  }

  /** Whether the job holds the lock right now (a realtime read). */
  @SuppressWarnings("rawtypes")
  public boolean holds(String jobId) throws IOException {
    GetResponse<Map> current = client.get(g -> g.index(index()).id(LOCK), Map.class);
    return current.found() && jobId.equals(current.source().get("jobId"));
  }

  /** Releases the lock if this job still holds it; never another job's lock. */
  @SuppressWarnings("rawtypes")
  public void unlock(String jobId) throws IOException {
    GetResponse<Map> current = client.get(g -> g.index(index()).id(LOCK), Map.class);
    if (current.found() && jobId.equals(current.source().get("jobId"))) {
      try {
        client.delete(
            d ->
                d.index(index())
                    .id(LOCK)
                    .ifSeqNo(current.seqNo())
                    .ifPrimaryTerm(current.primaryTerm()));
      } catch (ElasticsearchException e) {
        if (e.status() != 409 && e.status() != 404) {
          throw e;
        }
      }
    }
  }

  private Map<String, Object> lockDoc(String jobId, Duration lease) {
    return Map.of(
        "kind", "LOCK", "jobId", jobId, "leaseUntil", Elastic.millis(clock.instant().plus(lease)));
  }

  /** What an idempotency key was used for, unless it has expired (an expired key is removed). */
  @SuppressWarnings("rawtypes")
  public Optional<KeyBinding> keyBinding(String idempotencyKey) throws IOException {
    String id = KEY + hash(idempotencyKey);
    GetResponse<Map> key = client.get(g -> g.index(index()).id(id), Map.class);
    if (!key.found()) {
      return Optional.empty();
    }
    Map<?, ?> s = key.source();
    Instant createdAt = Elastic.instant(s.get("startedAt"));
    if (createdAt == null || createdAt.plus(keyTtl).isBefore(clock.instant())) {
      try {
        client.delete(
            d -> d.index(index()).id(id).ifSeqNo(key.seqNo()).ifPrimaryTerm(key.primaryTerm()));
      } catch (ElasticsearchException e) {
        if (e.status() != 409 && e.status() != 404) {
          throw e;
        }
      }
      return Optional.empty();
    }
    return Optional.of(
        new KeyBinding(
            (String) s.get("jobId"),
            s.get("scope") == null ? null : Scope.valueOf((String) s.get("scope")),
            (String) s.get("requestedBy"),
            createdAt));
  }

  /** Binds a key to a job, its scope and its caller; false when the key is bound already. */
  public boolean bindKey(String idempotencyKey, String jobId, Scope scope, String requestedBy)
      throws IOException {
    Map<String, Object> doc = new LinkedHashMap<>();
    doc.put("kind", "KEY");
    doc.put("jobId", jobId);
    doc.put("scope", scope.name());
    doc.put("requestedBy", requestedBy);
    doc.put("startedAt", Elastic.millis(clock.instant()));
    try {
      client.create(c -> c.index(index()).id(KEY + hash(idempotencyKey)).document(doc));
      return true;
    } catch (ElasticsearchException e) {
      if (e.status() == 409) {
        return false;
      }
      throw e;
    }
  }

  /** Removes idempotency keys older than their time to live. */
  @Scheduled(
      fixedDelayString = "${aerotrace.search.reindex.key-purge-delay:1h}",
      initialDelayString = "${aerotrace.search.reindex.key-purge-delay:1h}")
  public void purgeExpiredKeys() {
    long cutOff = clock.instant().minus(keyTtl).toEpochMilli();
    try {
      client.deleteByQuery(
          d ->
              d.index(index())
                  .query(
                      q ->
                          q.bool(
                              b ->
                                  b.filter(f -> f.term(t -> t.field("kind").value("KEY")))
                                      .filter(
                                          f ->
                                              f.range(
                                                  r ->
                                                      r.date(
                                                          x ->
                                                              x.field("startedAt")
                                                                  .lt(String.valueOf(cutOff))
                                                                  .format("epoch_millis")))))));
    } catch (IOException | RuntimeException e) {
      LOG.warn("Expired idempotency keys not purged: {}", e.getClass().getSimpleName());
    }
  }

  public void save(ReindexJob job) {
    jobs.save(
        new JobDocument(
            JobDocument.ID_PREFIX + job.jobId(),
            JobDocument.KIND,
            job.jobId(),
            job.status().name(),
            job.scope().name(),
            job.requestedBy(),
            job.startedAt(),
            job.finishedAt(),
            job.error(),
            job.results()));
  }

  public Optional<ReindexJob> find(String jobId) {
    return jobs.findById(JobDocument.ID_PREFIX + jobId)
        .map(
            d ->
                new ReindexJob(
                    d.jobId(),
                    Status.valueOf(d.status()),
                    Scope.valueOf(d.scope()),
                    d.requestedBy(),
                    d.startedAt(),
                    d.finishedAt(),
                    d.error(),
                    d.results() == null ? Map.of() : d.results()));
  }

  /** Keys are stored hashed: a client's key is never written back or used as an id as it is. */
  static String hash(String key) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
