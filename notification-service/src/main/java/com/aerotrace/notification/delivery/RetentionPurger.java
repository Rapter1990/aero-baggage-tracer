package com.aerotrace.notification.delivery;

import com.aerotrace.notification.config.NotificationProperties;
import com.aerotrace.notification.repository.DeliveryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Deletes finished notifications older than {@code aerotrace.notification.retention} (ADR 0005,
 * decision 14), in bounded batches, by the database's {@code now()}. Only SENT rows and FAILED rows
 * whose dead letter Kafka acknowledged are deleted. Purging removes the dedupe key, so the
 * retention stays longer than the topics' retention.
 */
@Component
public class RetentionPurger {

  private static final Logger LOG = LoggerFactory.getLogger(RetentionPurger.class);

  private final DeliveryRepository deliveries;
  private final NotificationProperties properties;

  public RetentionPurger(DeliveryRepository deliveries, NotificationProperties properties) {
    this.deliveries = deliveries;
    this.properties = properties;
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
    int batch = properties.purge().batchSize();
    int total = 0;
    int deleted;
    do {
      deleted = deliveries.purgeFinished(properties.retention(), batch);
      total += deleted;
    } while (deleted == batch);
    if (total > 0) {
      LOG.info("Purged {} finished notifications older than {}", total, properties.retention());
    }
    return total;
  }
}
