package com.aerotrace.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.aerotrace.gateway.support.GatewayTestBase;
import com.aerotrace.gateway.support.GatewayTestConfig;
import com.aerotrace.gateway.support.TestJwks;
import com.nimbusds.jose.jwk.RSAKey;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
import tools.jackson.databind.JsonNode;

class RateLimitAndRoutesTest extends GatewayTestBase {

  private static RSAKey key;

  @Autowired private Environment environment;

  @BeforeAll
  static void publishKey() {
    key = TestJwks.publish("kid-rl");
  }

  private static String tokenFor(String subject) {
    return TestJwks.sign(key, TestJwks.claims(subject, List.of("AGENT")).build());
  }

  @Test
  void exhaustedLimitReturns429ApiError() {
    String token = tokenFor("limited-" + UUID.randomUUID());

    for (int i = 0; i < GatewayTestConfig.ALLOWED_PER_KEY; i++) {
      assertThat(get("/api/v1/echo/ping", token).statusCode()).isEqualTo(200);
    }
    HttpResponse<String> limited = get("/api/v1/echo/ping", token);

    assertThat(limited.statusCode()).isEqualTo(429);
    assertThat(limited.headers().firstValue("Content-Type").orElse(""))
        .startsWith("application/json");
    JsonNode body = json(limited);
    assertThat(body.get("status").asInt()).isEqualTo(429);
    assertThat(body.get("code").asString()).isEqualTo("RATE_LIMITED");
    assertThat(body.get("correlationId").asString())
        .isEqualTo(limited.headers().firstValue("X-Correlation-Id").orElseThrow());
    assertThat(limited.headers().firstValue("X-RateLimit-Remaining")).contains("0");
  }

  @Test
  void limitsAreKeyedPerCallerSoOthersAreUnaffected() {
    String noisy = tokenFor("noisy-" + UUID.randomUUID());
    for (int i = 0; i <= GatewayTestConfig.ALLOWED_PER_KEY; i++) {
      get("/api/v1/echo/ping", noisy);
    }

    assertThat(get("/api/v1/echo/ping", tokenFor("quiet-" + UUID.randomUUID())).statusCode())
        .isEqualTo(200);
  }

  @Test
  void realServiceRoutesRequireATokenButTokenIssuanceDoesNot() {
    assertThat(get("/api/v1/bags/AB123456/journey", null).statusCode()).isEqualTo(401);
    assertThat(get("/api/v1/recovery-cases/1", null).statusCode()).isEqualTo(401);

    // No identity-service instance exists in this test, so these calls fail after security passed.
    for (String path :
        List.of(
            "/api/v1/auth/register",
            "/api/v1/auth/login",
            "/api/v1/auth/refresh-token",
            "/api/v1/auth/forgot-password",
            "/api/v1/auth/reset-password",
            "/api/v1/auth/logout")) {
      assertThat(send("POST", path, null).statusCode()).as(path).isNotIn(401, 403);
    }
  }

  @Test
  void theRestOfTheAuthPathsStillNeedAToken() {
    assertThat(get("/api/v1/auth/me", null).statusCode()).isEqualTo(401);
    assertThat(get("/api/v1/auth/login", null).statusCode()).isEqualTo(401);
    assertThat(send("POST", "/api/v1/auth/keys/rotate", null).statusCode()).isEqualTo(401);
  }

  /** Routes are resolved through Eureka (lb://) rather than hard-coded hosts. */
  @Test
  @SuppressWarnings("unchecked")
  void everyServiceIsRoutedThroughEurekaUnderApiV1() {
    List<Map<String, Object>> routes =
        (List<Map<String, Object>>)
            (List<?>)
                Binder.get(environment)
                    .bind("spring.cloud.gateway.server.webmvc.routes", Bindable.listOf(Map.class))
                    .get();

    List<String> lbHosts =
        routes.stream()
            .map(route -> String.valueOf(route.get("uri")))
            .filter(uri -> uri.startsWith("lb://"))
            .map(uri -> uri.substring("lb://".length()))
            .toList();

    assertThat(lbHosts)
        .containsExactlyInAnyOrder(
            "identity-service",
            "journey-service",
            "journey-service",
            "recovery-service",
            "capacity-service",
            "search-service",
            // OpenAPI definition routes (one per service for the Swagger UI dropdown).
            "identity-service",
            "journey-service",
            "recovery-service",
            "capacity-service",
            "search-service");
  }
}
