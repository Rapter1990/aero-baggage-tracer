package com.aerotrace.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.aerotrace.gateway.support.GatewayTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;

/** The production defaults for sending metrics and traces to the collector. */
class OtlpMetricsConfigTest extends GatewayTestBase {

  @Autowired private Environment environment;

  @Test
  void defaultsPointAtALocalCollector() {
    assertThat(environment.getProperty("management.otlp.metrics.export.url"))
        .isEqualTo("http://localhost:4318/v1/metrics");
    assertThat(environment.getProperty("management.opentelemetry.tracing.export.otlp.endpoint"))
        .isEqualTo("http://localhost:4318/v1/traces");
  }
}
