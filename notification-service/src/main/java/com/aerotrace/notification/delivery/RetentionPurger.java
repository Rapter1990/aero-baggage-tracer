package com.aerotrace.notification.delivery;

import com.aerotrace.notification.config.NotificationProperties;
import com.aerotrace.notification.repository.DeliveryRepository;
import java.time.Clock;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Deletes finished notifications older than {@code aerotrace.notification.retention} (ADR 0005,
 * decision 14), in bounded batches. Only SENT rows and FAILED rows whose dead letter Kafka
 * acknowledged are deleted. Purging removes the dedupe key, so the retention stays longer than the
 * topics' retention.
 */
@Component
public class RetentionPurger {

  private static final Logger LOG = LoggerFactory.getLogger(RetentionPurger.class);

  private final DeliveryRepository deliveries;
  private final NotificationProperties properties;
  private final Clock clock;

  public RetentionPurger(
      DeliveryRepository deliveries, NotificationProperties properties, Clock clock) {
    this.deliveries = deliveries;
    this.properties = properties;
    this.clock = clock;
  }

  @Scheduled(
      initialDelayString = "${aerotrace.notification.purge.delay:1h}",
      fixedDelayString = "${aerotrace.notification.purge.delay:1h}")
  void scheduledPurge() {
    try {
      purge();
    } catch (RuntimeException e) {
      LOG.warn("Retention purge failed: {}", e.getClass().getSimpleName());
    }
  }

  /**
   * @return how many rows were deleted
   */
  public int purge() {
    Instant cutoff = clock.instant().minus(properties.retention());
    int batch = properties.purge().batchSize();
    int total = 0;
    int deleted;
    do {
      deleted = deliveries.purgeFinished(cutoff, batch);
      total += deleted;
    } while (deleted == batch);
    if (total > 0) {
      LOG.info("Purged {} finished notifications created before {}", total, cutoff);
    }
    return total;
  }
}
