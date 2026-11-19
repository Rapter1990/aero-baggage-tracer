package com.aerotrace.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.auditing.DateTimeProvider;

class AuditConfigurationTest {

  private final AuditAutoConfiguration configuration = new AuditAutoConfiguration();

  @Test
  @DisplayName("the auditor provider is the security-based one")
  void auditorProvider() {
    assertThat(configuration.auditorProvider()).isInstanceOf(SecurityAuditorAware.class);
  }

  @Test
  @DisplayName("audit times are the current UTC instant")
  void dateTimeProvider() {
    DateTimeProvider provider = configuration.auditDateTimeProvider();

    Instant now = (Instant) provider.getNow().orElseThrow();

    assertThat(Duration.between(now, Instant.now()).abs()).isLessThan(Duration.ofSeconds(5));
  }

  @Test
  @DisplayName("a new auditable entity has no audit values until it is saved")
  void freshEntityHasNoAuditValues() {
    AuditableEntity entity = new AuditableEntity() {};

    assertThat(entity.getCreatedBy()).isNull();
    assertThat(entity.getCreatedAt()).isNull();
    assertThat(entity.getUpdatedBy()).isNull();
    assertThat(entity.getUpdatedAt()).isNull();
  }
}
