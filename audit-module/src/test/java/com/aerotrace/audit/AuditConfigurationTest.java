package com.aerotrace.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.annotation.ImportCandidates;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.auditing.DateTimeProvider;

class AuditConfigurationTest {

  private final AuditAutoConfiguration configuration = new AuditAutoConfiguration();

  @Test
  @DisplayName("the auditor provider is the security-based one")
  void auditorProvider() {
    assertThat(configuration.auditorProvider()).isInstanceOf(SecurityAuditorAware.class);
  }

  @Test
  @DisplayName("audit times are the current UTC instant when the service has no clock")
  void dateTimeProvider() {
    DateTimeProvider provider = configuration.auditDateTimeProvider(provider(null));

    Instant now = (Instant) provider.getNow().orElseThrow();

    assertThat(Duration.between(now, Instant.now()).abs()).isLessThan(Duration.ofSeconds(5));
  }

  @Test
  @DisplayName("audit times come from the service's own clock, so a fixed clock moves them all")
  void dateTimeProviderUsesTheServiceClock() {
    Instant fixed = Instant.parse("2026-01-02T03:04:05Z");
    DateTimeProvider provider =
        configuration.auditDateTimeProvider(provider(Clock.fixed(fixed, ZoneOffset.UTC)));

    assertThat(provider.getNow()).contains(fixed);
  }

  @Test
  @DisplayName("the auto-configuration is registered, so a service gets auditing by adding the jar")
  void autoConfigurationIsRegistered() {
    assertThat(ImportCandidates.load(AutoConfiguration.class, getClass().getClassLoader()))
        .contains(AuditAutoConfiguration.class.getName());
  }

  @Test
  @DisplayName("aerotrace.audit.jpa-auditing=false starts no auditing machinery")
  void canBeSwitchedOff() {
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(AuditAutoConfiguration.class))
        .withPropertyValues("aerotrace.audit.jpa-auditing=false")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context).doesNotHaveBean("auditorProvider");
              assertThat(context).doesNotHaveBean("auditDateTimeProvider");
            });
  }

  private static ObjectProvider<Clock> provider(Clock clock) {
    StaticListableBeanFactory beans = new StaticListableBeanFactory();
    if (clock != null) {
      beans.addBean("clock", clock);
    }
    return beans.getBeanProvider(Clock.class);
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
