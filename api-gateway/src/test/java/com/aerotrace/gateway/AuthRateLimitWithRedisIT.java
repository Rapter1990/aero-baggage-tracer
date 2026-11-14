package com.aerotrace.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.aerotrace.gateway.support.AbstractTestContainerConfiguration;
import com.aerotrace.gateway.support.GatewayClient;
import com.aerotrace.gateway.support.TestRoutesConfig;
import com.aerotrace.gateway.support.TestTokens;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;

/**
 * The authentication endpoints have their own, stricter bucket per endpoint and client address,
 * against a real, password-protected Redis. Here no proxy is trusted, so the peer address is the
 * client and a forged {@code X-Forwarded-For} changes nothing.
 */
@SpringBootTest(
    webEnvironment = WebEnvironment.RANDOM_PORT,
    properties = {
      "eureka.client.enabled=false",
      "spring.cloud.discovery.enabled=false",
      "aerotrace.gateway.audience=" + TestTokens.AUDIENCE,
      // The general bucket is generous; only the auth bucket (2 calls, slow refill) can cut in.
      "aerotrace.gateway.rate-limit.replenish-rate=1000",
      "aerotrace.gateway.rate-limit.burst-capacity=1000",
      "aerotrace.gateway.rate-limit.auth.replenish-rate=0.01",
      "aerotrace.gateway.rate-limit.auth.burst-capacity=2",
      "management.otlp.metrics.export.enabled=false",
      "management.tracing.export.otlp.enabled=false",
      "management.logging.export.otlp.enabled=false"
    })
@Import(TestRoutesConfig.class)
class AuthRateLimitWithRedisIT extends AbstractTestContainerConfiguration {

  private static final HttpClient CLIENT = HttpClient.newHttpClient();
  private static final int AUTH_BURST = 2;

  @Value("${local.server.port}")
  private int port;

  @DynamicPropertySource
  static void publicKey(DynamicPropertyRegistry registry) {
    registry.add("aerotrace.gateway.public-key", TestTokens::publicKeyPem);
  }

  private HttpResponse<String> post(String path, String forwardedFor) throws Exception {
    HttpRequest.Builder request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString("{}"));
    if (forwardedFor != null) {
      request.header("X-Forwarded-For", forwardedFor);
    }
    return CLIENT.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void aClientWhoEmptiesTheLoginBucketGetsA429WithRetryAfterFromRealRedis() throws Exception {
    // Own endpoint name per test run would not help: the key is per endpoint and address, and
    // every test client here is 127.0.0.1, so each test uses an endpoint nobody else touches.
    for (int i = 0; i < AUTH_BURST; i++) {
      assertThat(post("/api/v1/auth/forgot-password", null).statusCode())
          .as("call %s", i + 1)
          .isNotEqualTo(429);
    }

    HttpResponse<String> limited = post("/api/v1/auth/forgot-password", null);

    assertThat(limited.statusCode()).isEqualTo(429);
    JsonNode body = GatewayClient.json(limited);
    assertThat(body.get("code").asString()).isEqualTo("RATE_LIMITED");
    assertThat(body.get("status").asInt()).isEqualTo(429);
    long retryAfter = Long.parseLong(limited.headers().firstValue("Retry-After").orElseThrow());
    // 0.01 tokens a second: a token takes about 100 seconds.
    assertThat(retryAfter).isBetween(50L, 110L);
  }

  @Test
  void eachAuthEndpointHasItsOwnBucket() throws Exception {
    for (int i = 0; i <= AUTH_BURST; i++) {
      post("/api/v1/auth/reset-password", null);
    }

    assertThat(post("/api/v1/auth/reset-password", null).statusCode()).isEqualTo(429);
    assertThat(post("/api/v1/auth/logout", null).statusCode()).isNotEqualTo(429);
  }

  @Test
  void aForgedForwardedForHeaderDoesNotGetAFreshBucketWhenNoProxyIsTrusted() throws Exception {
    for (int i = 0; i < AUTH_BURST; i++) {
      post("/api/v1/auth/refresh-token", "9.9.9." + i);
    }

    HttpResponse<String> forged =
        post("/api/v1/auth/refresh-token", "203.0.113." + UUID.randomUUID().hashCode() % 200);

    assertThat(forged.statusCode()).isEqualTo(429);
  }

  @Test
  void callsToOtherEndpointsAreNotAffectedByAnEmptiedAuthBucket() throws Exception {
    for (int i = 0; i <= AUTH_BURST; i++) {
      post("/api/v1/auth/register", null);
    }
    assertThat(post("/api/v1/auth/register", null).statusCode()).isEqualTo(429);

    // Not an auth path: the generous general bucket applies and the call is simply unauthorized.
    assertThat(post("/api/v1/bags", null).statusCode()).isNotEqualTo(429);
  }
}
