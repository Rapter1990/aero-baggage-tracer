package com.aerotrace.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.aerotrace.gateway.support.GatewayTestBase;
import com.aerotrace.gateway.support.TestJwks;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class GatewaySecurityTest extends GatewayTestBase {

  private static RSAKey activeKey;

  @BeforeAll
  static void publishKey() {
    activeKey = TestJwks.publish("kid-1");
  }

  private static String token(String role) {
    return TestJwks.sign(
        activeKey, TestJwks.claims(UUID.randomUUID().toString(), List.of(role)).build());
  }

  private void assertProblem(HttpResponse<String> response, int status, String errorCode) {
    assertThat(response.statusCode()).isEqualTo(status);
    assertThat(response.headers().firstValue("Content-Type").orElse(""))
        .startsWith("application/json");
    JsonNode body = json(response);
    assertThat(body.get("status").asInt()).isEqualTo(status);
    assertThat(body.get("code").asString()).isEqualTo(errorCode);
    assertThat(body.get("correlationId").asString())
        .isEqualTo(response.headers().firstValue("X-Correlation-Id").orElseThrow());
  }

  @Test
  void validTokenReachesDownstream() {
    assertThat(get("/api/v1/echo/ping", token("PASSENGER")).statusCode()).isEqualTo(200);
  }

  @Test
  void missingTokenIs401ApiError() {
    assertProblem(get("/api/v1/echo/ping", null), 401, "UNAUTHENTICATED");
  }

  @Test
  void malformedTokenIs401() {
    assertProblem(get("/api/v1/echo/ping", "not-a-jwt"), 401, "UNAUTHENTICATED");
  }

  /** AT-16: an expired JWT is rejected with 401. */
  @Test
  void expiredTokenIs401() {
    Instant past = Instant.now().minusSeconds(3600);
    JWTClaimsSet expired =
        TestJwks.claims("expired-user", List.of("AGENT"))
            .issueTime(Date.from(past.minusSeconds(60)))
            .expirationTime(Date.from(past))
            .build();

    assertProblem(
        get("/api/v1/echo/ping", TestJwks.sign(activeKey, expired)), 401, "UNAUTHENTICATED");
  }

  @Test
  void tokenWithoutExpiryIs401() {
    JWTClaimsSet noExp =
        new JWTClaimsSet.Builder()
            .issuer(TestJwks.ISSUER)
            .audience(TestJwks.AUDIENCE)
            .subject("no-exp")
            .claim("roles", List.of("AGENT"))
            .build();

    assertProblem(
        get("/api/v1/echo/ping", TestJwks.sign(activeKey, noExp)), 401, "UNAUTHENTICATED");
  }

  @Test
  void wrongAudienceIs401() {
    JWTClaimsSet claims =
        TestJwks.claims("aud-user", List.of("AGENT")).audience("someone-else").build();

    assertProblem(
        get("/api/v1/echo/ping", TestJwks.sign(activeKey, claims)), 401, "UNAUTHENTICATED");
  }

  @Test
  void wrongIssuerIs401() {
    JWTClaimsSet claims =
        TestJwks.claims("iss-user", List.of("AGENT")).issuer("https://evil.example").build();

    assertProblem(
        get("/api/v1/echo/ping", TestJwks.sign(activeKey, claims)), 401, "UNAUTHENTICATED");
  }

  @Test
  void tokenSignedByUnpublishedKeyIs401() {
    RSAKey foreign = TestJwks.unpublished("kid-foreign");
    String forged = TestJwks.sign(foreign, TestJwks.claims("forger", List.of("ADMIN")).build());

    assertProblem(get("/api/v1/admin/x", forged), 401, "UNAUTHENTICATED");
  }

  @Test
  void unsignedAlgNoneTokenIs401() {
    String none = TestJwks.unsigned(TestJwks.claims("none-user", List.of("ADMIN")).build());

    assertProblem(get("/api/v1/admin/x", none), 401, "UNAUTHENTICATED");
  }

  /** AT-16: a valid token without the required role is rejected with 403. */
  @Test
  void wrongRoleIs403ApiError() {
    assertProblem(get("/api/v1/admin/x", token("PASSENGER")), 403, "ACCESS_DENIED");
  }

  @Test
  void requiredRoleIsAllowed() {
    assertThat(get("/api/v1/admin/x", token("ADMIN")).statusCode()).isEqualTo(200);
  }

  @Test
  void unknownRoleClaimGrantsNothing() {
    assertProblem(get("/api/v1/admin/x", token("SUPERUSER")), 403, "ACCESS_DENIED");
  }

  @Test
  void keyRotationKeepsUnexpiredTokensValid() {
    String beforeRotation = token("AGENT");
    RSAKey rotated = TestJwks.publish("kid-2");
    String afterRotation =
        TestJwks.sign(
            rotated, TestJwks.claims(UUID.randomUUID().toString(), List.of("AGENT")).build());

    assertThat(get("/api/v1/echo/ping", beforeRotation).statusCode()).isEqualTo(200);
    assertThat(get("/api/v1/echo/ping", afterRotation).statusCode()).isEqualTo(200);
  }

  @Test
  void healthIsPublicButOtherActuatorEndpointsNeedAdmin() {
    assertThat(get("/actuator/health", null).statusCode()).isEqualTo(200);
    assertThat(get("/actuator/prometheus", null).statusCode()).isEqualTo(401);
    assertThat(get("/actuator/prometheus", token("AGENT")).statusCode()).isEqualTo(403);
    HttpResponse<String> metrics = get("/actuator/prometheus", token("ADMIN"));
    assertThat(metrics.statusCode()).isEqualTo(200);
    assertThat(metrics.body()).contains("jvm_");
  }

  @Test
  void pathsOutsideTheApiAreDenied() {
    assertThat(get("/internal/anything", null).statusCode()).isEqualTo(401);
    assertThat(get("/internal/anything", token("ADMIN")).statusCode()).isEqualTo(403);
  }
}
