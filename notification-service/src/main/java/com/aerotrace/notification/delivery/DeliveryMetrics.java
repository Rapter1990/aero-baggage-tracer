package com.aerotrace.notification.delivery;

import com.aerotrace.notification.constant.MetricNames;
import com.aerotrace.notification.repository.DeliveryRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.stereotype.Component;

/**
 * The delivery gauges (ADR 0005, decision 12): how many notifications are not sent yet, and how
 * long the oldest due one has waited past its due time. Read from the database when the meters are
 * exported, against the database's {@code now()}, so every replica reports the same numbers.
 */
@Component
public class DeliveryMetrics implements MeterBinder {

  private final DeliveryRepository deliveries;

  public DeliveryMetrics(DeliveryRepository deliveries) {
    this.deliveries = deliveries;
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
      return deliveries.oldestDueAgeSeconds();
    } catch (RuntimeException databaseUnavailable) {
      return Double.NaN;
    }
  }
}
