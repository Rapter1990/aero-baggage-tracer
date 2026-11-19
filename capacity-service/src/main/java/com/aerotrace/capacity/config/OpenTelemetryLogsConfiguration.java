package com.aerotrace.capacity.config;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Connects the Logback appender declared in logback-spring.xml to Spring Boot's OpenTelemetry
 * instance, so log lines reach the collector (and Loki) with their trace id.
 */
@Configuration(proxyBeanMethods = false)
class OpenTelemetryLogsConfiguration {

  @Bean
  InitializingBean installOpenTelemetryLogAppender(OpenTelemetry openTelemetry) {
    return () -> OpenTelemetryAppender.install(openTelemetry);
  }
}
