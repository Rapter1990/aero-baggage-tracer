package com.aerotrace.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.aerotrace.identity.base.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;

/** The production defaults for sending traces, metrics and logs to the collector. */
class OtlpMetricsConfigTest extends AbstractIntegrationTest {

  @Autowired private Environment environment;

  @Test
  void defaultsPointAtALocalCollector() {
    assertThat(environment.getProperty("management.otlp.metrics.export.url"))
        .isEqualTo("http://localhost:4318/v1/metrics");
    assertThat(environment.getProperty("management.opentelemetry.tracing.export.otlp.endpoint"))
        .isEqualTo("http://localhost:4318/v1/traces");
    assertThat(environment.getProperty("management.opentelemetry.logging.export.otlp.endpoint"))
        .isEqualTo("http://localhost:4318/v1/logs");
  }

  @Test
  void everyMetricIsTaggedWithTheApplicationName() {
    assertThat(environment.getProperty("management.metrics.tags.application"))
        .isEqualTo("identity-service");
  }
}
