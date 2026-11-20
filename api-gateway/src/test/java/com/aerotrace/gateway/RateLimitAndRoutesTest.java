package com.aerotrace.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.aerotrace.gateway.support.GatewayTestBase;
import com.aerotrace.gateway.support.GatewayTestConfig;
import com.aerotrace.gateway.support.TestTokens;
import com.nimbusds.jose.jwk.RSAKey;
import java.net.http.HttpResponse;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
import org.springframework.http.server.PathContainer;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;
import tools.jackson.databind.JsonNode;

class RateLimitAndRoutesTest extends GatewayTestBase {

  private static RSAKey key;

  @Autowired private Environment environment;

  @BeforeAll
  static void publishKey() {
    key = TestTokens.trusted();
  }

  private static String tokenFor(String subject) {
    return TestTokens.sign(key, TestTokens.claims(subject, List.of("AGENT")).build());
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
    assertThat(get("/api/v1/recovery-policy", null).statusCode()).isEqualTo(401);
    assertThat(get("/api/v1/search/bags", null).statusCode()).isEqualTo(401);
    assertThat(get("/api/v1/search/index/status", null).statusCode()).isEqualTo(401);
    assertThat(send("POST", "/api/v1/admin/search/reindex", null).statusCode()).isEqualTo(401);
    assertThat(get("/api/v1/reports/airport/IST", null).statusCode()).isEqualTo(401);

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
            "recovery-service",
            "capacity-service",
            // Reports, bag search and the index status, the rebuild.
            "search-service",
            "search-service",
            "search-service",
            // OpenAPI definition routes (one per service for the Swagger UI dropdown).
            "identity-service",
            "journey-service",
            "recovery-service",
            "capacity-service",
            "search-service");
  }

  /**
   * The internal export endpoints of the owning services (#28, #29) are for service callers inside
   * the network only: no route may reach {@code /internal/**}, and the gateway refuses it itself.
   */
  @Test
  @SuppressWarnings("unchecked")
  void noRouteReachesTheInternalEndpoints() {
    List<Map<String, Object>> routes =
        (List<Map<String, Object>>)
            (List<?>)
                Binder.get(environment)
                    .bind("spring.cloud.gateway.server.webmvc.routes", Bindable.listOf(Map.class))
                    .get();
    PathPatternParser parser = new PathPatternParser();
    List<String> internal =
        List.of(
            "/internal/v1/export/cases",
            "/internal/v1/export/bags",
            "/internal/anything",
            "/api/v1/../internal/v1/export/cases");
    for (Map<String, Object> route : routes) {
      Object predicates = route.get("predicates");
      Collection<Object> all =
          predicates instanceof Map<?, ?> map
              ? (Collection<Object>) map.values()
              : (Collection<Object>) predicates;
      assertThat(all).as("predicates of route %s", route.get("id")).isNotEmpty();
      for (Object predicate : all) {
        String text = String.valueOf(predicate);
        if (!text.startsWith("Path=")) {
          continue;
        }
        for (String pattern : text.substring("Path=".length()).split(",")) {
          PathPattern path = parser.parse(pattern.trim());
          for (String target : internal) {
            assertThat(path.matches(PathContainer.parsePath(target)))
                .as("route %s (%s) must not match %s", route.get("id"), pattern, target)
                .isFalse();
          }
        }
      }
    }
    String token = tokenFor("internal-" + UUID.randomUUID());
    for (String target : internal.subList(0, 3)) {
      int status = get(target, token, "X-Service-Token", "guess-the-export-token").statusCode();
      assertThat(status).as(target).isIn(401, 403, 404);
    }
  }

  @Test
  @SuppressWarnings("unchecked")
  void everyRouteToAServiceThatAcceptsTheServiceTokenStripsIt() {
    List<Map<String, Object>> routes =
        (List<Map<String, Object>>)
            (List<?>)
                Binder.get(environment)
                    .bind("spring.cloud.gateway.server.webmvc.routes", Bindable.listOf(Map.class))
                    .get();

    // The search-service sends X-Service-Token to the export endpoints of the recovery-service
    // (and the journey-service); capacity accepts it from the recovery-service.
    for (String id :
        List.of(
            "capacity", "reports", "search", "search-admin", "recovery-cases", "recovery-policy")) {
      Map<String, Object> route =
          routes.stream().filter(r -> id.equals(r.get("id"))).findFirst().orElseThrow();
      assertThat(String.valueOf(route.get("filters")))
          .as("filters of route %s", id)
          .contains("RemoveRequestHeader=X-Service-Token");
    }
  }
}
