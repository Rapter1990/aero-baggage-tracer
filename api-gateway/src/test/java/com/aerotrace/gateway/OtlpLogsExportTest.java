package com.aerotrace.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.aerotrace.gateway.support.GatewayTestBase;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The gateway sends its log lines to the OpenTelemetry collector (and from there to Loki), each
 * tagged with the trace it was written in, so Grafana can jump from a log line to its trace.
 */
class OtlpLogsExportTest extends GatewayTestBase {

  private static final List<String> BODIES = new CopyOnWriteArrayList<>();
  private static final HttpServer COLLECTOR;

  static {
    try {
      COLLECTOR = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
      COLLECTOR.createContext("/v1/logs", OtlpLogsExportTest::handle);
      COLLECTOR.start();
    } catch (IOException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  private static void handle(HttpExchange exchange) throws IOException {
    // Protobuf, but names, messages and the raw trace id bytes survive as ISO-8859-1 characters.
    BODIES.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.ISO_8859_1));
    exchange.sendResponseHeaders(200, -1);
    exchange.close();
  }

  @Autowired private Tracer tracer;

  @DynamicPropertySource
  static void logs(DynamicPropertyRegistry registry) {
    registry.add("management.logging.export.otlp.enabled", () -> "true");
    registry.add(
        "management.opentelemetry.logging.export.otlp.endpoint",
        () -> "http://localhost:" + COLLECTOR.getAddress().getPort() + "/v1/logs");
  }

  private static String await(String marker) throws InterruptedException {
    long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
    Supplier<Optional<String>> find =
        () -> BODIES.stream().filter(body -> body.contains(marker)).findFirst();
    while (System.nanoTime() < deadline) {
      Optional<String> body = find.get();
      if (body.isPresent()) {
        return body.get();
      }
      Thread.sleep(200);
    }
    throw new AssertionError("No OTLP log push containing '" + marker + "' within 30 seconds");
  }

  @Test
  void pushesItsLogsToTheCollector() throws InterruptedException {
    LoggerFactory.getLogger("telemetry.test").info("hello from the api-gateway logs test");

    String body = await("hello from the api-gateway logs test");

    assertThat(body).contains("api-gateway");
  }

  @Test
  void logLinesWrittenInsideATraceCarryItsTraceId() throws InterruptedException {
    Span span = tracer.nextSpan().name("logs-test").start();
    String traceId = span.context().traceId();
    try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
      LoggerFactory.getLogger("telemetry.test").info("correlated line of the api-gateway");
    } finally {
      span.end();
    }
    String rawTraceId = new String(HexFormat.of().parseHex(traceId), StandardCharsets.ISO_8859_1);

    String body = await("correlated line of the api-gateway");

    assertThat(body).contains(rawTraceId);
  }
}
