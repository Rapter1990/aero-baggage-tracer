package com.aerotrace.capacity.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Gives back the slots of holds that nobody confirmed within the time to live (a caller that died
 * between holding and moving its case). A hold a case move confirmed is never touched: it is
 * released by its owner (the recovery-service) when the case is resolved, cancelled or closed. On
 * by default; see {@code aerotrace.capacity.hold.expiry-enabled} and ADR 0002.
 */
@Component
@ConditionalOnProperty(name = "aerotrace.capacity.hold.expiry-enabled", havingValue = "true")
public class HoldExpiryJob {

  private static final Logger LOG = LoggerFactory.getLogger(HoldExpiryJob.class);

  private final CapacityService capacity;

  public HoldExpiryJob(CapacityService capacity) {
    this.capacity = capacity;
  }

  @Scheduled(fixedDelayString = "${aerotrace.capacity.hold.sweep-delay:30s}")
  public void sweep() {
    try {
      int expired = capacity.expireStale();
      if (expired > 0) {
        LOG.info("Expired {} unconfirmed capacity holds", expired);
      }
    } catch (RuntimeException e) {
      // The next run tries again; the holds are still there.
      LOG.warn("Hold expiry run failed: {}", e.getClass().getSimpleName());
    }
  }
}
