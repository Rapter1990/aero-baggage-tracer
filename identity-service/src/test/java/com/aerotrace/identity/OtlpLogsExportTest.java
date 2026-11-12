package com.aerotrace.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.aerotrace.identity.base.AbstractIntegrationTest;
import com.aerotrace.identity.support.StubCollector;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Log lines reach the collector (and Loki), tagged with the trace they were written in. */
class OtlpLogsExportTest extends AbstractIntegrationTest {

  private static final StubCollector COLLECTOR = new StubCollector("/v1/logs");

  @Autowired private Tracer tracer;

  @DynamicPropertySource
  static void logs(DynamicPropertyRegistry registry) {
    registry.add("management.logging.export.otlp.enabled", () -> "true");
    registry.add(
        "management.opentelemetry.logging.export.otlp.endpoint", () -> COLLECTOR.url("/v1/logs"));
  }

  @Test
  void pushesItsLogsToTheCollector() throws InterruptedException {
    LoggerFactory.getLogger("telemetry.test").info("hello from the identity-service logs test");

    StubCollector.Push push = COLLECTOR.awaitPushContaining("hello from the identity-service");

    assertThat(push.body()).contains("identity-service");
  }

  @Test
  void logLinesWrittenInsideATraceCarryItsTraceId() throws InterruptedException {
    Span span = tracer.nextSpan().name("logs-test").start();
    String rawTraceId =
        new String(HexFormat.of().parseHex(span.context().traceId()), StandardCharsets.ISO_8859_1);
    try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
      LoggerFactory.getLogger("telemetry.test").info("correlated line of the identity-service");
    } finally {
      span.end();
    }

    StubCollector.Push push = COLLECTOR.awaitPushContaining("correlated line of the identity");

    assertThat(push.body()).contains(rawTraceId);
  }
}
