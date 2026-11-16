package com.aerotrace.journey.outbox;

import com.aerotrace.journey.constant.MetricNames;
import com.aerotrace.journey.repository.OutboxEventRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Gauges that show the state of the outbox: how many events wait and for how long the oldest has
 * waited. They read the table when the metrics are exported, so every replica reports the same
 * numbers (use max across instances). A failed read reports NaN, never an exception.
 */
@Component
public class OutboxMetrics {

  private static final Logger LOG = LoggerFactory.getLogger(OutboxMetrics.class);

  public OutboxMetrics(OutboxEventRepository outbox, MeterRegistry meters, Clock clock) {
    Gauge.builder(MetricNames.OUTBOX_PENDING, () -> read(() -> (double) outbox.countPending()))
        .description("Events waiting to be published")
        .register(meters);
    Gauge.builder(
            MetricNames.OUTBOX_OLDEST_PENDING_AGE,
            () ->
                read(
                    () ->
                        outbox
                            .oldestPendingCreatedAt()
                            .map(
                                oldest ->
                                    Math.max(
                                        0.0,
                                        Duration.between(oldest, Instant.now(clock)).toMillis()
                                            / 1000.0))
                            .orElse(0.0)))
        .description("Seconds the oldest waiting event has waited")
        .baseUnit("seconds")
        .register(meters);
  }

  private static double read(Supplier<Double> query) {
    try {
      return query.get();
    } catch (RuntimeException e) {
      LOG.debug("Outbox gauge could not read the table: {}", e.getClass().getSimpleName());
      return Double.NaN;
    }
  }
}
