package com.aerotrace.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.aerotrace.gateway.support.GatewayTestBase;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The gateway pushes its metrics over OTLP to the collector (which Prometheus scrapes), so no one
 * has to scrape the gateway's own admin-protected /actuator/prometheus endpoint.
 */
class OtlpMetricsExportTest extends GatewayTestBase {

  private record Push(String contentType, String body) {}

  private static final List<Push> PUSHES = new CopyOnWriteArrayList<>();
  private static final HttpServer COLLECTOR;

  static {
    try {
      COLLECTOR = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
      COLLECTOR.createContext("/v1/metrics", OtlpMetricsExportTest::handle);
      COLLECTOR.start();
    } catch (IOException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  private static void handle(HttpExchange exchange) throws IOException {
    byte[] body = exchange.getRequestBody().readAllBytes();
    PUSHES.add(
        new Push(
            exchange.getRequestHeaders().getFirst("Content-Type"),
            // Protobuf, but names such as the service and metric names are plain strings in it.
            new String(body, StandardCharsets.ISO_8859_1)));
    exchange.sendResponseHeaders(200, -1);
    exchange.close();
  }

  @DynamicPropertySource
  static void otlp(DynamicPropertyRegistry registry) {
    registry.add("management.otlp.metrics.export.enabled", () -> "true");
    registry.add("management.otlp.metrics.export.step", () -> "1s");
    registry.add(
        "management.otlp.metrics.export.url",
        () -> "http://localhost:" + COLLECTOR.getAddress().getPort() + "/v1/metrics");
  }

  private static <T> T await(Supplier<Optional<T>> condition) throws InterruptedException {
    long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
    while (System.nanoTime() < deadline) {
      Optional<T> result = condition.get();
      if (result.isPresent()) {
        return result.get();
      }
      Thread.sleep(200);
    }
    throw new AssertionError("No OTLP metrics push within 30 seconds");
  }

  @Test
  void pushesMetricsOfThisServiceToTheCollector() throws InterruptedException {
    Push push = await(() -> PUSHES.stream().filter(p -> p.body().contains("jvm.")).findFirst());

    assertThat(push.contentType()).contains("protobuf");
    assertThat(push.body()).contains("api-gateway");
  }
}
