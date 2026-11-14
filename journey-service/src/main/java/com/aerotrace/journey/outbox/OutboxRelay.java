package com.aerotrace.journey.outbox;

import com.aerotrace.journey.config.JourneyProperties;
import com.aerotrace.journey.constant.MetricNames;
import com.aerotrace.journey.repository.OutboxRepository;
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
 * Publishes the events in the outbox to Kafka, at least once. A batch is claimed with row locks
 * that other replicas skip, so replicas share the work and never publish the same row at the same
 * time. A row is marked published only after the broker has acknowledged it; if the process dies in
 * between, the row is published again, and consumers dedupe on the event id.
 */
@Component
public class OutboxRelay {

  private static final Logger LOG = LoggerFactory.getLogger(OutboxRelay.class);
  private static final long SEND_TIMEOUT_SECONDS = 5;

  private final OutboxRepository outbox;
  private final KafkaTemplate<String, String> kafka;
  private final JourneyProperties properties;
  private final Clock clock;
  private final MeterRegistry meters;
  private final TransactionTemplate transaction;

  public OutboxRelay(
      OutboxRepository outbox,
      KafkaTemplate<String, String> kafka,
      JourneyProperties properties,
      Clock clock,
      MeterRegistry meters,
      PlatformTransactionManager transactionManager) {
    this.transaction = new TransactionTemplate(transactionManager);
    this.outbox = outbox;
    this.kafka = kafka;
    this.properties = properties;
    this.clock = clock;
    this.meters = meters;
  }

  @Scheduled(fixedDelayString = "${aerotrace.journey.outbox.relay-delay:1s}")
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
    if (!outbox.tryLockRelay()) {
      // Another replica is relaying; it keeps the order, this run has nothing to do.
      return 0;
    }
    List<OutboxRepository.Row> batch = outbox.claimPending(properties.outbox().batchSize());
    int sent = 0;
    for (OutboxRepository.Row row : batch) {
      try {
        kafka
            .send(row.topic(), row.key(), row.payload())
            .get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        outbox.recordFailure(row.id());
        break;
      } catch (Exception e) {
        if (refusedForGood(e)) {
          // Set aside, so one bad event cannot block every event behind it.
          outbox.park(row.id(), Instant.now(clock));
          meters.counter(MetricNames.OUTBOX_PARKED).increment();
          LOG.error("An outbox event was refused by the broker and set aside: {}", rootName(e));
          continue;
        }
        // Stop at the first failure: later events of the same bag must not overtake this one.
        outbox.recordFailure(row.id());
        meters.counter(MetricNames.OUTBOX_FAILED).increment();
        LOG.warn("Could not publish an outbox event: {}", rootName(e));
        break;
      }
      outbox.markPublished(row.id(), Instant.now(clock));
      meters.counter(MetricNames.OUTBOX_PUBLISHED).increment();
      sent++;
    }
    return sent;
  }

  /** True when the broker refused the event in a way that retrying cannot change. */
  private static boolean refusedForGood(Throwable failure) {
    for (Throwable t = failure; t != null; t = t.getCause()) {
      if (t instanceof org.apache.kafka.common.errors.RetriableException
          || t instanceof java.util.concurrent.TimeoutException) {
        return false;
      }
      if (t instanceof org.apache.kafka.common.KafkaException) {
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
