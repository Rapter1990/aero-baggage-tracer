package com.aerotrace.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.aerotrace.gateway.support.GatewayTestBase;
import com.aerotrace.gateway.support.TestTokens;
import com.nimbusds.jose.jwk.RSAKey;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/** A downstream service that answers too slowly must not hold gateway threads indefinitely. */
@TestPropertySource(properties = "spring.http.clients.read-timeout=1s")
class DownstreamTimeoutTest extends GatewayTestBase {

  private static RSAKey key;

  @BeforeAll
  static void publishKey() {
    key = TestTokens.trusted();
  }

  @Test
  void aSlowDownstreamIsCutOffAtTheReadTimeout() {
    String token =
        TestTokens.sign(
            key, TestTokens.claims(UUID.randomUUID().toString(), List.of("AGENT")).build());
    long start = System.nanoTime();

    HttpResponse<String> response = get("/api/v1/slow/report", token);
    Duration took = Duration.ofNanos(System.nanoTime() - start);

    // The stub answers after 3 seconds with 200; a working 1 second timeout gives up first.
    assertThat(response.statusCode()).isGreaterThanOrEqualTo(500);
    assertThat(took).isLessThan(Duration.ofMillis(2500));
  }
}
