package com.aerotrace.journey.config;

import com.aerotrace.journey.constant.MetricNames;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Shows a rollout that stayed on the {@code expand} step (ADR 0007). Changeset 006 makes {@code
 * scan_source} mandatory and runs only with the {@code contract} context; without it the column
 * stays nullable while the entity says it is not. Nothing fails, so this says so: a warning at
 * start-up and the gauge {@value MetricNames#MIGRATION_CONTRACT_PENDING} (1 while 006 has not been
 * applied, 0 once it has, NaN when the changelog table cannot be read). A staged rollout expects 1
 * for one release; an alert should fire when it stays 1 for longer than that.
 */
@Component
public class ContractStepMonitor {

  /** The changeset that makes scan_source mandatory. */
  public static final String CONTRACT_CHANGESET = "006-scan-source-not-null";

  private static final Logger LOG = LoggerFactory.getLogger(ContractStepMonitor.class);

  private final EntityManager entityManager;

  public ContractStepMonitor(EntityManager entityManager, MeterRegistry meters) {
    this.entityManager = entityManager;
    Gauge.builder(MetricNames.MIGRATION_CONTRACT_PENDING, this, ContractStepMonitor::gaugeValue)
        .description("1 while the contract step (scan_source NOT NULL) has not been applied")
        .register(meters);
  }

  /** True while changeset 006 is not in the changelog table. */
  public boolean pending() {
    Number applied =
        (Number)
            entityManager
                .createNativeQuery("SELECT count(*) FROM journey.databasechangelog WHERE id = ?1")
                .setParameter(1, CONTRACT_CHANGESET)
                .getSingleResult();
    return applied.longValue() == 0;
  }

  private double gaugeValue() {
    try {
      return pending() ? 1.0 : 0.0;
    } catch (RuntimeException e) {
      LOG.debug(
          "Contract step gauge could not read the changelog: {}", e.getClass().getSimpleName());
      return Double.NaN;
    }
  }

  @EventListener(ApplicationReadyEvent.class)
  void warnWhenPending() {
    try {
      if (pending()) {
        LOG.warn(
            "Changeset {} (scan_source NOT NULL) has not been applied: JOURNEY_LIQUIBASE_CONTEXTS"
                + " does not contain \"contract\". Expected for one release of a staged rollout;"
                + " add \"contract\" once every replica writes scan_source (ADR 0007).",
            CONTRACT_CHANGESET);
      }
    } catch (RuntimeException e) {
      LOG.debug("Contract step check skipped: {}", e.getClass().getSimpleName());
    }
  }
}
