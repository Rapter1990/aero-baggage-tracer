package com.aerotrace.journey.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.aerotrace.journey.base.AbstractBaseServiceTest;
import com.aerotrace.journey.constant.MetricNames;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ContractStepMonitorTest extends AbstractBaseServiceTest {

  private final EntityManager entityManager = mock(EntityManager.class);
  private final Query query = mock(Query.class);
  private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

  private ContractStepMonitor monitor(Object applied) {
    when(entityManager.createNativeQuery(anyString())).thenReturn(query);
    when(query.setParameter(anyInt(), org.mockito.ArgumentMatchers.any())).thenReturn(query);
    when(query.getSingleResult()).thenReturn(applied);
    return new ContractStepMonitor(entityManager, meters);
  }

  private double gauge() {
    return meters.get(MetricNames.MIGRATION_CONTRACT_PENDING).gauge().value();
  }

  @Test
  @DisplayName("AT-17 the contract step is pending until changeset 006 is in the changelog")
  void pendingUntilTheChangesetIsApplied() {
    assertThat(monitor(0L).pending()).isTrue();
  }

  @Test
  @DisplayName("AT-17 the contract step is not pending once changeset 006 is applied")
  void notPendingOnceApplied() {
    assertThat(monitor(1L).pending()).isFalse();
  }

  @Test
  @DisplayName("AT-17 the gauge is 1 while pending and 0 once applied")
  void gaugeFollowsTheChangelog() {
    monitor(0L);
    assertThat(gauge()).isEqualTo(1.0);
  }

  @Test
  @DisplayName("AT-17 the gauge is 0 once the contract step is applied")
  void gaugeIsZeroWhenApplied() {
    monitor(1L);
    assertThat(gauge()).isEqualTo(0.0);
  }

  @Test
  @DisplayName("AT-17 an unreadable changelog gives NaN, never an exception")
  void unreadableChangelogGivesNaN() {
    when(entityManager.createNativeQuery(anyString())).thenThrow(new IllegalStateException("down"));
    new ContractStepMonitor(entityManager, meters);

    assertThat(gauge()).isNaN();
  }
}
