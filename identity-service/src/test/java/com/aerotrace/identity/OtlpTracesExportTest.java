package com.aerotrace.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.aerotrace.identity.base.AbstractIntegrationTest;
import com.aerotrace.identity.support.StubCollector;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Spans are exported over OTLP to the collector (and on to Tempo), named after this service. */
class OtlpTracesExportTest extends AbstractIntegrationTest {

  private static final StubCollector COLLECTOR = new StubCollector("/v1/traces");

  @Autowired private Tracer tracer;

  @DynamicPropertySource
  static void otlp(DynamicPropertyRegistry registry) {
    registry.add("management.tracing.export.otlp.enabled", () -> "true");
    registry.add(
        "management.opentelemetry.tracing.export.otlp.endpoint", () -> COLLECTOR.url("/v1/traces"));
  }

  @Test
  void exportsSpansOfThisServiceToTheCollector() throws InterruptedException {
    Span span = tracer.nextSpan().name("traces-export-test").start();
    String rawTraceId =
        new String(HexFormat.of().parseHex(span.context().traceId()), StandardCharsets.ISO_8859_1);
    span.end();

    StubCollector.Push push = COLLECTOR.awaitPushContaining("traces-export-test");

    assertThat(push.body()).contains("identity-service").contains(rawTraceId);
  }
}
