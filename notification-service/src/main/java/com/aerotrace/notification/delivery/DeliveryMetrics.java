package com.aerotrace.notification.delivery;

import com.aerotrace.notification.constant.MetricNames;
import com.aerotrace.notification.repository.DeliveryRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.stereotype.Component;

/**
 * The delivery gauges (ADR 0005, decision 12): how many notifications are not sent yet, and how
 * long the oldest due one has waited past its due time. Read from the database when the meters are
 * exported, so every replica reports the same numbers.
 */
@Component
public class DeliveryMetrics implements MeterBinder {

  private final DeliveryRepository deliveries;
  private final Clock clock;

  public DeliveryMetrics(DeliveryRepository deliveries, Clock clock) {
    this.deliveries = deliveries;
    this.clock = clock;
  }

  @Override
  public void bindTo(MeterRegistry registry) {
    Gauge.builder(MetricNames.PENDING, this, DeliveryMetrics::pending)
        .description("Notifications not sent yet")
        .register(registry);
    Gauge.builder(MetricNames.OLDEST_DUE_AGE, this, DeliveryMetrics::oldestDueAgeSeconds)
        .description("Seconds the oldest due notification has waited past its due time")
        .baseUnit("seconds")
        .register(registry);
  }

  double pending() {
    try {
      return deliveries.countPending();
    } catch (RuntimeException databaseUnavailable) {
      return Double.NaN;
    }
  }

  double oldestDueAgeSeconds() {
    try {
      Instant now = clock.instant();
      Instant oldest = deliveries.oldestDue(now);
      return oldest == null ? 0 : Duration.between(oldest, now).toMillis() / 1000.0;
    } catch (RuntimeException databaseUnavailable) {
      return Double.NaN;
    }
  }
}
