package com.aerotrace.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.aerotrace.gateway.support.GatewayTestBase;
import com.aerotrace.gateway.support.TestJwks;
import com.nimbusds.jose.jwk.RSAKey;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class CorrelationAndForwardingTest extends GatewayTestBase {

  private static RSAKey key;

  @BeforeAll
  static void publishKey() {
    key = TestJwks.publish("kid-fwd");
  }

  private static String token() {
    return TestJwks.sign(
        key, TestJwks.claims(UUID.randomUUID().toString(), List.of("AGENT")).build());
  }

  @Test
  void generatesCorrelationIdOnResponseAndDownstreamRequest() {
    HttpResponse<String> response = get("/api/v1/echo/ping", token());

    String onResponse = response.headers().firstValue("X-Correlation-Id").orElseThrow();
    assertThat(onResponse).isNotBlank();
    assertThat(json(response).get("correlationId").asString()).isEqualTo(onResponse);
  }

  @Test
  void keepsAWellFormedInboundCorrelationId() {
    HttpResponse<String> response =
        get("/api/v1/echo/ping", token(), "X-Correlation-Id", "client-trace-42");

    assertThat(response.headers().firstValue("X-Correlation-Id")).contains("client-trace-42");
    assertThat(json(response).get("correlationId").asString()).isEqualTo("client-trace-42");
  }

  @Test
  void replacesAMalformedInboundCorrelationId() {
    HttpResponse<String> response =
        get("/api/v1/echo/ping", token(), "X-Correlation-Id", "bad value with spaces!");

    String onResponse = response.headers().firstValue("X-Correlation-Id").orElseThrow();
    assertThat(onResponse).isNotEqualTo("bad value with spaces!").matches("[A-Za-z0-9._-]{1,64}");
    assertThat(json(response).get("correlationId").asString()).isEqualTo(onResponse);
  }

  @Test
  void correlationIdIsPresentOnErrorResponsesToo() {
    HttpResponse<String> unauthenticated = get("/api/v1/echo/ping", null);

    assertThat(unauthenticated.statusCode()).isEqualTo(401);
    assertThat(unauthenticated.headers().firstValue("X-Correlation-Id")).isPresent();
  }

  /** Token forwarding: the downstream service receives the caller's bearer token unchanged. */
  @Test
  void forwardsTheBearerTokenUnchanged() {
    String token = token();

    JsonNode seen = json(get("/api/v1/echo/ping", token));

    assertThat(seen.get("authorization").asString()).isEqualTo("Bearer " + token);
  }

  @Test
  void passesIdempotencyKeyThrough() {
    JsonNode seen = json(get("/api/v1/echo/ping", token(), "Idempotency-Key", "scan-123"));

    assertThat(seen.get("idempotencyKey").asString()).isEqualTo("scan-123");
  }
}
