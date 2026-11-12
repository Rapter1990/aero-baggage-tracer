package com.aerotrace.identity.keys;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Makes sure a signing key exists at start-up and keeps the key set healthy: introduces a new key
 * when one is due and retires keys whose tokens have all expired. Safe on every replica.
 */
@Component
class KeyMaintenance implements ApplicationRunner {

  private static final Logger log = LoggerFactory.getLogger(KeyMaintenance.class);

  private final SigningKeyService keys;

  KeyMaintenance(SigningKeyService keys) {
    this.keys = keys;
  }

  @Override
  public void run(ApplicationArguments args) {
    keys.ensureKeyExists();
  }

  @Scheduled(
      initialDelayString = "${aerotrace.identity.keys.maintenance-interval:PT5M}",
      fixedDelayString = "${aerotrace.identity.keys.maintenance-interval:PT5M}")
  void maintain() {
    try {
      keys.rotateIfDue();
      keys.retireExpired();
    } catch (RuntimeException e) {
      log.warn("Signing key maintenance failed ({})", e.getClass().getSimpleName());
    }
  }
}
