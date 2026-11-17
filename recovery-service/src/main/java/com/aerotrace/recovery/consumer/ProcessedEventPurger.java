package com.aerotrace.recovery.consumer;

import com.aerotrace.recovery.config.RecoveryProperties;
import com.aerotrace.recovery.repository.ProcessedEventRepository;
import java.time.Clock;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Keeps {@code processed_event} from growing for ever. An event id only has to be remembered while
 * Kafka can still deliver the event again, so rows older than the configured retention (longer than
 * the topics' retention) are removed.
 */
@Component
public class ProcessedEventPurger {

  private static final Logger LOG = LoggerFactory.getLogger(ProcessedEventPurger.class);

  private final ProcessedEventRepository processed;
  private final RecoveryProperties properties;
  private final Clock clock;
  private final TransactionTemplate transaction;

  public ProcessedEventPurger(
      ProcessedEventRepository processed,
      RecoveryProperties properties,
      Clock clock,
      PlatformTransactionManager transactionManager) {
    this.processed = processed;
    this.properties = properties;
    this.clock = clock;
    this.transaction = new TransactionTemplate(transactionManager);
  }

  @Scheduled(fixedDelayString = "PT1H", initialDelayString = "PT1H")
  void purgeScheduled() {
    try {
      int removed = purge();
      if (removed > 0) {
        LOG.info("Forgot {} processed event ids", removed);
      }
    } catch (RuntimeException e) {
      LOG.warn("Purge of processed events failed: {}", e.getClass().getSimpleName());
    }
  }

  /** Removes the ids past the retention and returns how many there were. */
  public int purge() {
    Instant cutoff = Instant.now(clock).minus(properties.consumers().dedupeRetention());
    Integer removed = transaction.execute(status -> processed.deleteProcessedBefore(cutoff));
    return removed == null ? 0 : removed;
  }
}
