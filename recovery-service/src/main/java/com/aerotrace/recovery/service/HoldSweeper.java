package com.aerotrace.recovery.service;

import com.aerotrace.recovery.config.RecoveryProperties;
import java.time.Clock;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The compensating workflow of the capacity saga: holds that ran out are released and their cases
 * cancelled, and releases that failed earlier are retried. Runs in every replica; both operations
 * are safe to repeat (a moved case is skipped, releasing a released hold is a no-op).
 */
@Component
class HoldSweeper {

  private static final Logger LOG = LoggerFactory.getLogger(HoldSweeper.class);

  private final RecoveryCaseService service;
  private final RecoveryProperties properties;
  private final Clock clock;

  HoldSweeper(RecoveryCaseService service, RecoveryProperties properties, Clock clock) {
    this.service = service;
    this.properties = properties;
    this.clock = clock;
  }

  @Scheduled(fixedDelayString = "${aerotrace.recovery.capacity.sweep-delay:30s}")
  void sweep() {
    try {
      int expired =
          service.expireHolds(Instant.now(clock).minus(properties.capacity().holdTimeout()));
      int retried = service.retryReleases();
      if (expired > 0 || retried > 0) {
        LOG.info("Hold sweep: {} expired, {} releases retried", expired, retried);
      }
    } catch (RuntimeException e) {
      // The next run tries again.
      LOG.warn("Hold sweep failed: {}", e.getClass().getSimpleName());
    }
  }
}
