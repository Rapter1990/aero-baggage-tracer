package com.aerotrace.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.aerotrace.identity.base.AbstractIntegrationTest;
import com.aerotrace.identity.support.StubCollector;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Metrics are pushed over OTLP to the collector, which Prometheus scrapes. */
class OtlpMetricsExportTest extends AbstractIntegrationTest {

  private static final StubCollector COLLECTOR = new StubCollector("/v1/metrics");

  @DynamicPropertySource
  static void otlp(DynamicPropertyRegistry registry) {
    registry.add("management.otlp.metrics.export.enabled", () -> "true");
    registry.add("management.otlp.metrics.export.step", () -> "1s");
    registry.add("management.otlp.metrics.export.url", () -> COLLECTOR.url("/v1/metrics"));
  }

  @Test
  void pushesMetricsOfThisServiceToTheCollector() throws InterruptedException {
    StubCollector.Push push = COLLECTOR.awaitPushContaining("jvm.");

    assertThat(push.contentType()).contains("protobuf");
    assertThat(push.body()).contains("identity-service");
  }
}
