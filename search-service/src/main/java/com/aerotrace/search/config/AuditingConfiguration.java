package com.aerotrace.search.config;

import com.aerotrace.audit.SecurityAuditorAware;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.auditing.DateTimeProvider;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.elasticsearch.config.EnableElasticsearchAuditing;

/**
 * Spring Data auditing for the documents saved through a repository (the rebuild jobs):
 * {@code @CreatedDate}, {@code @LastModifiedDate}, {@code @CreatedBy} and {@code @LastModifiedBy},
 * with the acting user of {@link SecurityAuditorAware} (the token's subject, {@code system} outside
 * a request, see ADR 0003) and instants from the service's own {@link Clock}.
 *
 * <p>The read models are written by bulk scripted upserts, which fire no auditing callbacks, so
 * they carry no audit fields (ADR 0004).
 */
@Configuration(proxyBeanMethods = false)
@EnableElasticsearchAuditing(
    auditorAwareRef = "auditorProvider",
    dateTimeProviderRef = "auditDateTimeProvider")
class AuditingConfiguration {

  @Bean
  AuditorAware<String> auditorProvider() {
    return new SecurityAuditorAware();
  }

  @Bean
  DateTimeProvider auditDateTimeProvider(Clock clock) {
    return () -> Optional.of(Instant.now(clock));
  }
}
