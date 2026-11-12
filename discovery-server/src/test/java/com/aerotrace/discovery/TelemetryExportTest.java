package com.aerotrace.discovery;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The discovery server reports to the OpenTelemetry collector like every other service: metrics
 * (for Prometheus) and traces (for Tempo) are pushed over OTLP. A stand-in collector records them.
 */
@SpringBootTest(
    webEnvironment = WebEnvironment.RANDOM_PORT,
    properties = {"spring.security.user.name=eureka", "spring.security.user.password=test-secret"})
class TelemetryExportTest {

  private record Push(String path, String contentType, String body) {}

  private static final List<Push> PUSHES = new CopyOnWriteArrayList<>();
  private static final HttpServer COLLECTOR;

  static {
    try {
      COLLECTOR = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
      COLLECTOR.createContext("/", TelemetryExportTest::handle);
      COLLECTOR.start();
    } catch (IOException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  private static void handle(HttpExchange exchange) throws IOException {
    byte[] body = exchange.getRequestBody().readAllBytes();
    PUSHES.add(
        new Push(
            exchange.getRequestURI().getPath(),
            exchange.getRequestHeaders().getFirst("Content-Type"),
            // Protobuf, but names such as the service and metric names are plain strings in it.
            new String(body, StandardCharsets.ISO_8859_1)));
    exchange.sendResponseHeaders(200, -1);
    exchange.close();
  }

  @LocalServerPort private int port;

  @Autowired private Tracer tracer;

  @DynamicPropertySource
  static void collector(DynamicPropertyRegistry registry) {
    String base = "http://localhost:" + COLLECTOR.getAddress().getPort();
    registry.add("management.otlp.metrics.export.url", () -> base + "/v1/metrics");
    registry.add("management.otlp.metrics.export.step", () -> "1s");
    registry.add(
        "management.opentelemetry.tracing.export.otlp.endpoint", () -> base + "/v1/traces");
    registry.add("management.tracing.sampling.probability", () -> "1.0");
    registry.add("management.opentelemetry.logging.export.otlp.endpoint", () -> base + "/v1/logs");
  }

  private static <T> T await(String what, Supplier<Optional<T>> condition)
      throws InterruptedException {
    long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
    while (System.nanoTime() < deadline) {
      Optional<T> result = condition.get();
      if (result.isPresent()) {
        return result.get();
      }
      Thread.sleep(200);
    }
    throw new AssertionError("No OTLP push for " + what + " within 30 seconds");
  }

  @Test
  void pushesItsMetricsToTheCollector() throws InterruptedException {
    Push push =
        await(
            "metrics",
            () ->
                PUSHES.stream()
                    .filter(p -> p.path().equals("/v1/metrics"))
                    .filter(p -> p.body().contains("jvm."))
                    .findFirst());

    assertThat(push.contentType()).contains("protobuf");
    assertThat(push.body()).contains("discovery-server");
  }

  @Test
  void pushesTracesOfItsRequestsToTheCollector() throws InterruptedException, IOException {
    HttpResponse<String> health =
        send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/actuator/health")));
    assertThat(health.statusCode()).isEqualTo(200);

    Push push =
        await(
            "traces",
            () ->
                PUSHES.stream()
                    .filter(p -> p.path().equals("/v1/traces"))
                    .filter(p -> p.body().contains("discovery-server"))
                    .findFirst());

    assertThat(push.contentType()).contains("protobuf");
  }

  private static HttpResponse<String> send(HttpRequest.Builder request)
      throws IOException, InterruptedException {
    return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void pushesItsLogsToTheCollector() throws InterruptedException {
    LoggerFactory.getLogger("telemetry.test").info("hello from the  logs test");

    Push push =
        await(
            "logs",
            () ->
                PUSHES.stream()
                    .filter(p -> p.path().equals("/v1/logs"))
                    .filter(p -> p.body().contains("hello from the  logs test"))
                    .findFirst());

    assertThat(push.contentType()).contains("protobuf");
    assertThat(push.body()).contains("");
  }

  @Test
  void logLinesWrittenInsideATraceCarryItsTraceId() throws InterruptedException {
    Span span = tracer.nextSpan().name("logs-test").start();
    String traceId = span.context().traceId();
    try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
      LoggerFactory.getLogger("telemetry.test").info("correlated line of the ");
    } finally {
      span.end();
    }
    // In OTLP protobuf the trace id is 16 raw bytes, which show up as plain characters here.
    String rawTraceId =
        new String(HexFormat.of().parseHex(traceId), java.nio.charset.StandardCharsets.ISO_8859_1);

    Push push =
        await(
            "correlated logs",
            () ->
                PUSHES.stream()
                    .filter(p -> p.path().equals("/v1/logs"))
                    .filter(p -> p.body().contains("correlated line of the "))
                    .findFirst());

    assertThat(push.body()).contains(rawTraceId);
  }
}
