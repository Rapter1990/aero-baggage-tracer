package com.aerotrace.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.aerotrace.gateway.support.FakeEureka;
import com.aerotrace.gateway.support.GatewayTestBase;
import java.time.Duration;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The gateway announces itself to Eureka and fetches the registry its lb:// routes resolve. */
class EurekaRegistrationTest extends GatewayTestBase {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  /**
   * Turns the Eureka client back on (the shared test setup disables it) and points it at the fake.
   */
  @DynamicPropertySource
  static void eureka(DynamicPropertyRegistry registry) {
    registry.add("eureka.client.enabled", () -> "true");
    registry.add("spring.cloud.discovery.enabled", () -> "true");
    registry.add("eureka.client.service-url.defaultZone", FakeEureka::serviceUrl);
  }

  /** Registration is asynchronous, so poll for it instead of sleeping a fixed time. */
  private static <T> T await(Supplier<Optional<T>> condition) throws InterruptedException {
    long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
    while (System.nanoTime() < deadline) {
      Optional<T> result = condition.get();
      if (result.isPresent()) {
        return result.get();
      }
      Thread.sleep(200);
    }
    throw new AssertionError(
        "Condition not met within 30 seconds; Eureka calls: " + FakeEureka.calls());
  }

  @Test
  void registersAsApiGatewayAndIsUp() throws InterruptedException {
    FakeEureka.Call registration =
        await(
            () ->
                FakeEureka.calls().stream()
                    .filter(c -> c.method().equals("POST"))
                    .filter(c -> c.path().endsWith("/apps/API-GATEWAY"))
                    .findFirst());

    JsonNode instance = JSON.readTree(registration.body()).get("instance");
    assertThat(instance.get("app").asString()).isEqualTo("API-GATEWAY");
    assertThat(instance.get("instanceId").asString()).startsWith("api-gateway:localhost:");
    assertThat(instance.get("status").asString()).isEqualTo("UP");
    assertThat(instance.get("vipAddress").asString()).isEqualTo("api-gateway");
    assertThat(instance.get("healthCheckUrl").asString()).endsWith("/actuator/health");
    assertThat(instance.get("leaseInfo").get("renewalIntervalInSecs").asInt()).isEqualTo(10);
    assertThat(instance.get("leaseInfo").get("durationInSecs").asInt()).isEqualTo(30);
  }

  @Test
  void fetchesTheRegistryForLoadBalancedRoutes() throws InterruptedException {
    FakeEureka.Call fetch =
        await(
            () ->
                FakeEureka.calls().stream()
                    .filter(c -> c.method().equals("GET"))
                    .filter(c -> c.path().contains("/apps"))
                    .findFirst());

    assertThat(fetch.path()).startsWith("/eureka/apps");
  }
}
