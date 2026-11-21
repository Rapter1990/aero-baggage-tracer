package com.aerotrace.search.reindex;

import java.time.Instant;
import java.util.Map;

/**
 * A rebuild job as the API shows it.
 *
 * @param jobId the job
 * @param status RUNNING, SUCCEEDED or FAILED
 * @param scope ALL, CASES or JOURNEYS
 * @param requestedBy the subject of the admin who started it
 * @param startedAt when it started
 * @param finishedAt when it ended, null while it runs
 * @param error why it failed (a fixed text, never a source record), null otherwise
 * @param results per index: the new and previous index, source and index counts and checksums
 */
public record ReindexJob(
    String jobId,
    Status status,
    Scope scope,
    String requestedBy,
    Instant startedAt,
    Instant finishedAt,
    String error,
    Map<String, Object> results) {

  public enum Status {
    RUNNING,
    SUCCEEDED,
    FAILED
  }

  public enum Scope {
    ALL,
    CASES,
    JOURNEYS;

    boolean cases() {
      return this != JOURNEYS;
    }

    boolean journeys() {
      return this != CASES;
    }
  }
}
