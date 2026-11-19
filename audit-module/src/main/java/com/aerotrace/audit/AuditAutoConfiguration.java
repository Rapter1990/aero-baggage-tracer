package com.aerotrace.audit;

import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.data.auditing.DateTimeProvider;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/**
 * Switches Spring Data JPA auditing on for any service that has this module and JPA. A service only
 * extends {@link AuditableEntity}; there is nothing to configure.
 *
 * <p>A service that uses the module only for {@link SecurityAuditorAware#currentAuditor()} (it
 * writes its audit columns in native SQL and has no {@link AuditableEntity}) sets {@code
 * aerotrace.audit.jpa-auditing=false}, so no auditing machinery is started for nothing, and a
 * service that declares its own {@code @EnableJpaAuditing} can do the same.
 */
@AutoConfiguration
@ConditionalOnClass(EntityManager.class)
@ConditionalOnProperty(
    prefix = "aerotrace.audit",
    name = "jpa-auditing",
    havingValue = "true",
    matchIfMissing = true)
@EnableJpaAuditing(
    auditorAwareRef = "auditorProvider",
    dateTimeProviderRef = "auditDateTimeProvider")
public class AuditAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean(name = "auditorProvider")
  AuditorAware<String> auditorProvider() {
    return new SecurityAuditorAware();
  }

  /**
   * UTC instants from the service's own {@link Clock} (the one that stamps its other timestamps, so
   * a fixed clock in a test moves them all together); the system clock when there is none.
   */
  @Bean
  @ConditionalOnMissingBean(name = "auditDateTimeProvider")
  DateTimeProvider auditDateTimeProvider(ObjectProvider<Clock> clock) {
    return () -> Optional.of(Instant.now(clock.getIfAvailable(Clock::systemUTC)));
  }
}
