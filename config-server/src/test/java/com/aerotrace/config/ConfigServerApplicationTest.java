package com.aerotrace.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.RestClient;

@SpringBootTest(
    webEnvironment = WebEnvironment.RANDOM_PORT,
    properties = {"eureka.client.enabled=false", "EUREKA_PASSWORD=leaky-secret", "EUREKA_USER=svc"})
class ConfigServerApplicationTest {

  @LocalServerPort private int port;

  private RestClient client() {
    return RestClient.builder()
        .baseUrl("http://localhost:" + port)
        .defaultStatusHandler(HttpStatusCode::isError, (request, response) -> {})
        .build();
  }

  /** Resolves a property like a client would: the first property source holding the key wins. */
  @SuppressWarnings("unchecked")
  private Object resolve(String application, String profile, String key) {
    Map<String, Object> environment =
        client().get().uri("/{app}/{profile}", application, profile).retrieve().body(Map.class);
    List<Map<String, Object>> sources =
        (List<Map<String, Object>>) environment.get("propertySources");
    return sources.stream()
        .map(source -> (Map<String, Object>) source.get("source"))
        .filter(source -> source.containsKey(key))
        .map(source -> source.get(key))
        .findFirst()
        .orElse(null);
  }

  @Test
  void healthEndpointIsUp() {
    String body = client().get().uri("/actuator/health").retrieve().body(String.class);

    assertThat(body).contains("\"status\":\"UP\"");
  }

  @Test
  void sharedRiskThresholdIs20ForEveryProfile() {
    for (String profile : List.of("default", "dev", "compose", "k8s")) {
      assertThat(resolve("risk-streams-service", profile, "aerotrace.risk.threshold-minutes"))
          .as("profile %s", profile)
          .isEqualTo(20);
    }
  }

  @Test
  void unknownProfileStillGetsSharedDefaultsButNoEndpoints() {
    assertThat(resolve("journey-service", "nope", "aerotrace.risk.threshold-minutes"))
        .isEqualTo(20);
    assertThat(resolve("journey-service", "nope", "aerotrace.kafka.bootstrap-servers")).isNull();
  }

  @Test
  void profileFilesProvideEnvironmentSpecificEndpoints() {
    assertThat(resolve("journey-service", "dev", "aerotrace.kafka.bootstrap-servers"))
        .isEqualTo("localhost:9092");
    assertThat(resolve("journey-service", "compose", "aerotrace.kafka.bootstrap-servers"))
        .isEqualTo("kafka:9092");
    assertThat(resolve("journey-service", "k8s", "aerotrace.db.host")).isEqualTo("postgres");
  }

  @Test
  void eurekaUrlsCarryCredentialPlaceholdersTheClientResolvesItself() {
    // The registry needs HTTP Basic credentials. They must never be stored in the repo or resolved
    // (and so sent over the wire) by the config server: the client fills them from its own env.
    for (String profile : List.of("dev", "compose")) {
      Object zone = resolve("journey-service", profile, "eureka.client.service-url.defaultZone");

      assertThat(zone).as("profile %s", profile).isInstanceOf(String.class);
      assertThat((String) zone)
          .contains("${EUREKA_USER:eureka}:${EUREKA_PASSWORD}@")
          .doesNotContain("leaky-secret");
    }
  }

  @Test
  void sharedEurekaInstanceIdFollowsTheApplicationHostPortFormat() {
    // Every service registers as <application>:<hostname>:<port>; the client fills the
    // placeholders.
    for (String profile : List.of("default", "dev", "compose", "k8s")) {
      assertThat(resolve("journey-service", profile, "eureka.instance.instance-id"))
          .as("profile %s", profile)
          .isEqualTo(
              "${spring.application.name}:${EUREKA_INSTANCE_HOSTNAME:localhost}:${server.port}");
    }
  }

  @Test
  void profileOverridesWinOverSharedFile() {
    // Resolve with profile order dev,compose: the last profile is the most specific.
    assertThat(resolve("journey-service", "dev,compose", "aerotrace.kafka.bootstrap-servers"))
        .isEqualTo("kafka:9092");
  }

  @Test
  void onlyHealthAndInfoActuatorEndpointsAreExposedOnTheServerItself() {
    String links = client().get().uri("/actuator").retrieve().body(String.class);

    assertThat(links).contains("health").doesNotContain("refresh");
  }
}
