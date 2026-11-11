package com.aerotrace.config;

import static org.assertj.core.api.Assertions.assertThat;

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
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * What config-server tells Eureka about itself. The instance id follows the platform-wide format
 * {@code <application>:<hostname>:<port>}, e.g. {@code config-server:localhost:1111}, instead of
 * Eureka's default that embeds the machine's own host name.
 */
@SpringBootTest(
    webEnvironment = WebEnvironment.RANDOM_PORT,
    properties = {
      "EUREKA_PASSWORD=test-secret",
      "management.otlp.metrics.export.enabled=false",
      "management.tracing.export.otlp.enabled=false"
    })
class EurekaRegistrationTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final List<String[]> CALLS = new CopyOnWriteArrayList<>();
  private static final HttpServer EUREKA;

  static {
    try {
      EUREKA = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
      EUREKA.createContext("/eureka/", EurekaRegistrationTest::handle);
      EUREKA.start();
    } catch (IOException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  private static void handle(HttpExchange exchange) throws IOException {
    String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    CALLS.add(new String[] {exchange.getRequestMethod(), exchange.getRequestURI().getPath(), body});
    if ("GET".equals(exchange.getRequestMethod())) {
      byte[] registry =
          "{\"applications\":{\"versions__delta\":\"1\",\"apps__hashcode\":\"\",\"application\":[]}}"
              .getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().add("Content-Type", "application/json");
      exchange.sendResponseHeaders(200, registry.length);
      exchange.getResponseBody().write(registry);
    } else {
      exchange.sendResponseHeaders("POST".equals(exchange.getRequestMethod()) ? 204 : 200, -1);
    }
    exchange.close();
  }

  @DynamicPropertySource
  static void eureka(DynamicPropertyRegistry registry) {
    registry.add("eureka.client.enabled", () -> "true");
    registry.add(
        "eureka.client.service-url.defaultZone",
        () -> "http://eureka:test-secret@localhost:" + EUREKA.getAddress().getPort() + "/eureka/");
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
    throw new AssertionError("Condition not met within 30 seconds");
  }

  @Test
  void registersWithTheSameInstanceIdFormatAsTheOtherServices() throws InterruptedException {
    String[] registration =
        await(
            () ->
                CALLS.stream()
                    .filter(call -> call[0].equals("POST"))
                    .filter(call -> call[1].endsWith("/apps/CONFIG-SERVER"))
                    .findFirst());

    JsonNode instance = JSON.readTree(registration[2]).get("instance");
    assertThat(instance.get("app").asString()).isEqualTo("CONFIG-SERVER");
    assertThat(instance.get("instanceId").asString()).matches("config-server:localhost:\\d+");
    assertThat(instance.get("hostName").asString()).isEqualTo("localhost");
  }
}
