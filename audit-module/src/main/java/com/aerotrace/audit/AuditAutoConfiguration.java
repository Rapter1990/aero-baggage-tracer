package com.aerotrace.audit;

import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.Optional;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.data.auditing.DateTimeProvider;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/**
 * Switches Spring Data JPA auditing on for any service that has this module and JPA. A service only
 * extends {@link AuditableEntity}; there is nothing to configure.
 */
@AutoConfiguration
@ConditionalOnClass(EntityManager.class)
@EnableJpaAuditing(
    auditorAwareRef = "auditorProvider",
    dateTimeProviderRef = "auditDateTimeProvider")
public class AuditAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean(name = "auditorProvider")
  AuditorAware<String> auditorProvider() {
    return new SecurityAuditorAware();
  }

  /** UTC instants, the same kind every other timestamp of the platform is. */
  @Bean
  @ConditionalOnMissingBean(name = "auditDateTimeProvider")
  DateTimeProvider auditDateTimeProvider() {
    return () -> Optional.of(Instant.now());
  }
}
