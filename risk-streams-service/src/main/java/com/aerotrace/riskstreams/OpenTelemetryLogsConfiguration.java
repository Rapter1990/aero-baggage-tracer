package com.aerotrace.riskstreams;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Connects the Logback appender of logback-spring.xml to Spring Boot's OpenTelemetry instance. */
@Configuration(proxyBeanMethods = false)
class OpenTelemetryLogsConfiguration {

  @Bean
  InitializingBean installOpenTelemetryLogAppender(OpenTelemetry openTelemetry) {
    return () -> OpenTelemetryAppender.install(openTelemetry);
  }
}
