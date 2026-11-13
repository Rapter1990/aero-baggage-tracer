package com.aerotrace.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.aerotrace.gateway.support.AbstractTestContainerConfiguration;
import com.aerotrace.gateway.support.GatewayClient;
import com.aerotrace.gateway.support.TestRoutesConfig;
import com.aerotrace.gateway.support.TestTokens;
import com.nimbusds.jose.jwk.RSAKey;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;

/**
 * The whole gateway with its real Redis-backed rate limiter (no in-memory stand-in): a client is
 * cut off with a ApiError 429 once its bucket in a real, password-protected Redis is empty.
 */
@SpringBootTest(
    webEnvironment = WebEnvironment.RANDOM_PORT,
    properties = {
      "eureka.client.enabled=false",
      "spring.cloud.discovery.enabled=false",
      "aerotrace.gateway.audience=" + TestTokens.AUDIENCE,
      // A small bucket with a slow refill keeps the outcome independent of test timing.
      "aerotrace.gateway.rate-limit.replenish-rate=1",
      "aerotrace.gateway.rate-limit.burst-capacity=3",
      "management.otlp.metrics.export.enabled=false",
      "management.tracing.export.otlp.enabled=false",
      "management.logging.export.otlp.enabled=false"
    })
@Import(TestRoutesConfig.class)
class RateLimitWithRedisIT extends AbstractTestContainerConfiguration {

  private static final int BURST = 3;

  private static RSAKey key;

  @Value("${local.server.port}")
  private int port;

  @DynamicPropertySource
  static void publicKey(DynamicPropertyRegistry registry) {
    registry.add("aerotrace.gateway.public-key", TestTokens::publicKeyPem);
  }

  @BeforeAll
  static void publishKey() {
    key = TestTokens.trusted();
  }

  private static String tokenFor(String subject) {
    return TestTokens.sign(key, TestTokens.claims(subject, List.of("AGENT")).build());
  }

  private HttpResponse<String> call(String token) {
    return new GatewayClient(port).get("/api/v1/echo/ping", token);
  }

  @Test
  void aCallerWhoEmptiesTheBucketGets429FromRealRedis() {
    String token = tokenFor("redis-limited-" + UUID.randomUUID());

    for (int i = 0; i < BURST; i++) {
      assertThat(call(token).statusCode()).as("call %s", i + 1).isEqualTo(200);
    }
    HttpResponse<String> limited = call(token);

    assertThat(limited.statusCode()).isEqualTo(429);
    assertThat(limited.headers().firstValue("Content-Type").orElse(""))
        .startsWith("application/json");
    JsonNode body = GatewayClient.json(limited);
    assertThat(body.get("code").asString()).isEqualTo("RATE_LIMITED");
    assertThat(body.get("status").asInt()).isEqualTo(429);
    assertThat(limited.headers().firstValue("Retry-After")).isPresent();
    assertThat(limited.headers().firstValue("X-RateLimit-Remaining")).contains("0");
  }

  @Test
  void eachCallerHasHisOwnBucketInRedis() {
    String noisy = tokenFor("redis-noisy-" + UUID.randomUUID());
    for (int i = 0; i <= BURST; i++) {
      call(noisy);
    }

    assertThat(call(noisy).statusCode()).isEqualTo(429);
    assertThat(call(tokenFor("redis-quiet-" + UUID.randomUUID())).statusCode()).isEqualTo(200);
  }

  @Test
  void theBucketRefillsOverTime() throws InterruptedException {
    String token = tokenFor("redis-refill-" + UUID.randomUUID());
    for (int i = 0; i < BURST; i++) {
      call(token);
    }
    assertThat(call(token).statusCode()).isEqualTo(429);

    Thread.sleep(1300);

    assertThat(call(token).statusCode()).isEqualTo(200);
  }
}
