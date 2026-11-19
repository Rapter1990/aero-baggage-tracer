package com.aerotrace.search.reindex;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch.core.GetResponse;
import com.aerotrace.search.elastic.Elastic;
import com.aerotrace.search.elastic.SupportIndex;
import com.aerotrace.search.reindex.ReindexJob.Scope;
import com.aerotrace.search.reindex.ReindexJob.Status;
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
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Jobs, the rebuild lock and idempotency keys, kept in Elasticsearch itself (the service has no
 * database), so they hold across instances:
 *
 * <ul>
 *   <li>{@code lock}: created with {@code op_type=create}, so only one instance gets it; it has a
 *       lease that the running job renews, and an expired lease (its instance died) may be taken
 *       over, with a sequence-number check so two instances cannot both take it;
 *   <li>{@code key:<sha-256 of the Idempotency-Key>}: created once and names the job, so a repeated
 *       request gets the same job;
 *   <li>{@code job:<jobId>}: the job itself.
 * </ul>
 */
@Component
public class ReindexJobStore {

  private static final String LOCK = "lock";
  private static final String KEY = "key:";
  private static final String JOB = "job:";

  private final ElasticsearchClient client;
  private final Clock clock;

  public ReindexJobStore(ElasticsearchClient client, Clock clock) {
    this.client = client;
    this.clock = clock;
  }

  private static String index() {
    return SupportIndex.JOBS.index();
  }

  /** Takes the lock for a job, or returns false when another job holds a live lease. */
  @SuppressWarnings("rawtypes")
  public boolean lock(String jobId, Duration lease) throws IOException {
    Map<String, Object> doc = lockDoc(jobId, lease);
    try {
      client.create(c -> c.index(index()).id(LOCK).document(doc));
      return true;
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
      return false;
    }
    try {
      client.index(
          i ->
              i.index(index())
                  .id(LOCK)
                  .ifSeqNo(current.seqNo())
                  .ifPrimaryTerm(current.primaryTerm())
                  .document(doc));
      return true;
    } catch (ElasticsearchException e) {
      if (e.status() == 409) {
        return false;
      }
      throw e;
    }
  }

  /** Extends the lease of the job's lock. */
  public void renew(String jobId, Duration lease) throws IOException {
    client.index(i -> i.index(index()).id(LOCK).document(lockDoc(jobId, lease)));
  }

  /** Releases the lock if this job still holds it. */
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

  /** The job an idempotency key started, if any. */
  @SuppressWarnings("rawtypes")
  public Optional<String> jobOfKey(String idempotencyKey) throws IOException {
    GetResponse<Map> key =
        client.get(g -> g.index(index()).id(KEY + hash(idempotencyKey)), Map.class);
    return key.found() ? Optional.of((String) key.source().get("jobId")) : Optional.empty();
  }

  /** Binds a key to a job; false when the key already names another job. */
  public boolean bindKey(String idempotencyKey, String jobId) throws IOException {
    try {
      client.create(
          c ->
              c.index(index())
                  .id(KEY + hash(idempotencyKey))
                  .document(Map.of("kind", "KEY", "jobId", jobId)));
      return true;
    } catch (ElasticsearchException e) {
      if (e.status() == 409) {
        return false;
      }
      throw e;
    }
  }

  public void save(ReindexJob job) throws IOException {
    Map<String, Object> doc = new LinkedHashMap<>();
    doc.put("kind", "JOB");
    doc.put("jobId", job.jobId());
    doc.put("status", job.status().name());
    doc.put("scope", job.scope().name());
    doc.put("requestedBy", job.requestedBy());
    doc.put("startedAt", Elastic.millis(job.startedAt()));
    doc.put("finishedAt", Elastic.millis(job.finishedAt()));
    doc.put("error", job.error());
    doc.put("results", job.results());
    doc.values().removeIf(Objects::isNull);
    client.index(i -> i.index(index()).id(JOB + job.jobId()).document(doc));
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  public Optional<ReindexJob> find(String jobId) throws IOException {
    GetResponse<Map> get = client.get(g -> g.index(index()).id(JOB + jobId), Map.class);
    if (!get.found()) {
      return Optional.empty();
    }
    Map<String, Object> s = get.source();
    return Optional.of(
        new ReindexJob(
            (String) s.get("jobId"),
            Status.valueOf((String) s.get("status")),
            Scope.valueOf((String) s.get("scope")),
            (String) s.get("requestedBy"),
            Elastic.instant(s.get("startedAt")),
            Elastic.instant(s.get("finishedAt")),
            (String) s.get("error"),
            (Map<String, Object>) s.getOrDefault("results", Map.of())));
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
