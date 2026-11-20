package com.aerotrace.search.indexer;

import com.aerotrace.search.constant.MetricNames;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;

/**
 * Counts what the indexer did and remembers the newest {@code publishedAt} it has had acknowledged
 * (the watermark the reports and the lag are computed from).
 */
@Component
public class IndexMetrics {

  private final MeterRegistry registry;
  private final Timer delay;
  private final Clock clock;
  private final AtomicReference<Instant> newestIndexed = new AtomicReference<>();

  public IndexMetrics(MeterRegistry registry, Clock clock) {
    this.registry = registry;
    this.clock = clock;
    this.delay =
        Timer.builder(MetricNames.INDEX_DELAY)
            .description("Time from an event's publication to its acknowledgement by Elasticsearch")
            .register(registry);
  }

  public void count(String topic, Outcome outcome) {
    registry
        .counter(
            MetricNames.INDEX_EVENTS,
            MetricNames.TAG_OUTCOME,
            outcome.tag(),
            MetricNames.TAG_TOPIC,
            topic)
        .increment();
  }

  /** An event was acknowledged by Elasticsearch (written, or found older than the index). */
  public void acknowledged(Instant publishedAt) {
    if (publishedAt == null) {
      return;
    }
    Duration age = Duration.between(publishedAt, clock.instant());
    delay.record(age.isNegative() ? Duration.ZERO : age);
    newestIndexed.accumulateAndGet(
        publishedAt, (current, next) -> current == null || next.isAfter(current) ? next : current);
  }

  /** The newest {@code publishedAt} acknowledged by this instance, or null before the first. */
  public Instant newestIndexed() {
    return newestIndexed.get();
  }
}
