package com.aerotrace.search.constant;

/** Metric names (ADR 0004, decision 9). The Grafana panel is added with #19. */
public final class MetricNames {

  /** Gauge, seconds: 0 when caught up, otherwise the age of the newest indexed event. */
  public static final String INDEX_LAG = "search.index.lag";

  /** Gauge: events of the consumed topics the consumer group has not committed yet. */
  public static final String INDEX_LAG_EVENTS = "search.index.lag.events";

  /** Counter by {@code outcome} (indexed, stale, duplicate, failed) and {@code topic}. */
  public static final String INDEX_EVENTS = "search.index.events";

  /** Timer: from an event's {@code publishedAt} to its acknowledgement by Elasticsearch. */
  public static final String INDEX_DELAY = "search.index.delay";

  /** Counter by {@code result} (succeeded, failed) of rebuild jobs. */
  public static final String REINDEX_RUNS = "search.reindex.runs";

  public static final String TAG_OUTCOME = "outcome";
  public static final String TAG_TOPIC = "topic";
  public static final String TAG_RESULT = "result";

  private MetricNames() {}
}
