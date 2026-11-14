package com.aerotrace.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.aerotrace.gateway.support.AbstractTestContainerConfiguration;
import com.aerotrace.gateway.support.TestRoutesConfig;
import com.aerotrace.gateway.support.TestTokens;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Behind a trusted reverse proxy (here: the test client itself, on the loopback address) the real
 * client address comes from {@code X-Forwarded-For}: clients get separate buckets, and what a
 * client prepends to the header does not help it.
 */
@SpringBootTest(
    webEnvironment = WebEnvironment.RANDOM_PORT,
    properties = {
      "eureka.client.enabled=false",
      "spring.cloud.discovery.enabled=false",
      "aerotrace.gateway.audience=" + TestTokens.AUDIENCE,
      "aerotrace.gateway.trusted-proxies=127.0.0.1,::1,0:0:0:0:0:0:0:1",
      "aerotrace.gateway.rate-limit.replenish-rate=1000",
      "aerotrace.gateway.rate-limit.burst-capacity=1000",
      "aerotrace.gateway.rate-limit.auth.replenish-rate=0.01",
      "aerotrace.gateway.rate-limit.auth.burst-capacity=2",
      "management.otlp.metrics.export.enabled=false",
      "management.tracing.export.otlp.enabled=false",
      "management.logging.export.otlp.enabled=false"
    })
@Import(TestRoutesConfig.class)
class TrustedProxyRateLimitWithRedisIT extends AbstractTestContainerConfiguration {

  private static final HttpClient CLIENT = HttpClient.newHttpClient();

  @Value("${local.server.port}")
  private int port;

  @DynamicPropertySource
  static void publicKey(DynamicPropertyRegistry registry) {
    registry.add("aerotrace.gateway.public-key", TestTokens::publicKeyPem);
  }

  private HttpResponse<String> login(String forwardedFor) throws Exception {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/auth/login"))
            .header("Content-Type", "application/json")
            .header("X-Forwarded-For", forwardedFor)
            .POST(HttpRequest.BodyPublishers.ofString("{}"))
            .build();
    return CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void clientsBehindTheProxyHaveTheirOwnBuckets() throws Exception {
    for (int i = 0; i < 3; i++) {
      login("198.51.100.1");
    }

    assertThat(login("198.51.100.1").statusCode()).isEqualTo(429);
    assertThat(login("198.51.100.2").statusCode()).isNotEqualTo(429);
  }

  @Test
  void whatAClientPutsInFrontOfTheProxysEntryDoesNotGiveItAFreshBucket() throws Exception {
    for (int i = 0; i < 3; i++) {
      login("1.1." + i + ".1, 198.51.100.50");
    }

    // The proxy saw 198.51.100.50 every time; the forged leading addresses are ignored.
    assertThat(login("2.2.2.2, 198.51.100.50").statusCode()).isEqualTo(429);
  }
}
