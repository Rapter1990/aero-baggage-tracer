package com.aerotrace.capacity.outbox;

import com.aerotrace.capacity.config.CapacityProperties;
import com.aerotrace.capacity.constant.MetricNames;
import com.aerotrace.capacity.model.entity.OutboxEventEntity;
import com.aerotrace.capacity.repository.OutboxEventRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Publishes the events in the outbox to Kafka, at least once. One replica relays at a time (an
 * advisory lock), so the events of a resource leave in the order they were stored. A row is marked
 * published only after the broker has acknowledged it; if the process dies in between, the row is
 * published again and consumers dedupe on the event id. A committed event is never lost: while
 * Kafka is down the rows simply stay pending and go out when it is back.
 */
@Component
public class OutboxRelay {

  private static final Logger LOG = LoggerFactory.getLogger(OutboxRelay.class);
  private static final long SEND_TIMEOUT_SECONDS = 5;

  private final OutboxEventRepository outbox;
  private final KafkaTemplate<String, String> kafka;
  private final CapacityProperties properties;
  private final Clock clock;
  private final MeterRegistry meters;
  private final TransactionTemplate transaction;

  public OutboxRelay(
      OutboxEventRepository outbox,
      KafkaTemplate<String, String> kafka,
      CapacityProperties properties,
      Clock clock,
      MeterRegistry meters,
      PlatformTransactionManager transactionManager) {
    this.transaction = new TransactionTemplate(transactionManager);
    this.outbox = outbox;
    this.kafka = kafka;
    this.properties = properties;
    this.clock = clock;
    this.meters = meters;
    // outbox_pending_count: events waiting to be published (alert on a backlog).
    Gauge.builder(MetricNames.OUTBOX_PENDING, outbox, OutboxEventRepository::countPending)
        .description("Outbox events waiting to be published")
        .register(meters);
  }

  @Scheduled(fixedDelayString = "${aerotrace.capacity.outbox.relay-delay:1s}")
  public void relay() {
    try {
      publishPending();
    } catch (RuntimeException e) {
      // The next run tries again; the rows are still unpublished.
      LOG.warn("Outbox relay run failed: {}", e.getClass().getSimpleName());
    }
  }

  /** Publishes one batch in order, in one transaction, and returns how many events were sent. */
  public int publishPending() {
    Integer sent = transaction.execute(status -> sendBatch());
    return sent == null ? 0 : sent;
  }

  private int sendBatch() {
    if (!outbox.tryLockRelay(OutboxEventRepository.RELAY_LOCK)) {
      return 0;
    }
    List<OutboxEventEntity> batch = outbox.claimPending(properties.outbox().batchSize());
    int sent = 0;
    for (OutboxEventEntity row : batch) {
      try {
        kafka
            .send(row.getTopic(), row.getMessageKey(), row.getPayload())
            .get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        row.recordFailure();
        break;
      } catch (Exception e) {
        if (refusedForGood(e)) {
          // Set aside, so one bad event cannot block every event behind it.
          row.park(Instant.now(clock));
          meters.counter(MetricNames.OUTBOX_PARKED).increment();
          LOG.error("An outbox event was refused by the broker and set aside: {}", rootName(e));
          continue;
        }
        // Stop at the first failure: later events of the same case must not overtake this one.
        row.recordFailure();
        meters.counter(MetricNames.OUTBOX_FAILED).increment();
        LOG.warn("Could not publish an outbox event: {}", rootName(e));
        break;
      }
      row.markPublished(Instant.now(clock));
      meters.counter(MetricNames.OUTBOX_PUBLISHED).increment();
      sent++;
    }
    return sent;
  }

  /**
   * True only for failures that belong to this one event and that retrying cannot change: it is too
   * large for the broker, its topic name is invalid, or it cannot be serialized. Any other failure
   * (authorization, configuration, a broker problem) says nothing about the event: it stays pending
   * and the relay stops and tries again, so a misconfigured broker can never park the whole outbox.
   */
  static boolean refusedForGood(Throwable failure) {
    for (Throwable t = failure; t != null; t = t.getCause()) {
      if (t instanceof org.apache.kafka.common.errors.RecordTooLargeException
          || t instanceof org.apache.kafka.common.errors.InvalidTopicException
          || t instanceof org.apache.kafka.common.errors.SerializationException) {
        return true;
      }
    }
    return false;
  }

  private static String rootName(Throwable failure) {
    Throwable root = failure;
    while (root.getCause() != null) {
      root = root.getCause();
    }
    return root.getClass().getSimpleName();
  }

  /** Removes published rows past the retention time. */
  @Scheduled(fixedDelayString = "PT1H", initialDelayString = "PT1H")
  @Transactional
  public void purge() {
    outbox.deletePublishedBefore(Instant.now(clock).minus(properties.outbox().retention()));
  }
}
