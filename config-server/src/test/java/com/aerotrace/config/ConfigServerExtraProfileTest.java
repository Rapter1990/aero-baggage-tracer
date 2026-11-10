package com.aerotrace.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;

/** An operator-set SPRING_PROFILES_ACTIVE must not switch the native backend off. */
@SpringBootTest(
    webEnvironment = WebEnvironment.RANDOM_PORT,
    properties = "eureka.client.enabled=false")
@ActiveProfiles("compose")
class ConfigServerExtraProfileTest {

  @LocalServerPort private int port;

  @Test
  void stillServesConfigWhenAnotherProfileIsActive() {
    String body =
        RestClient.create("http://localhost:" + port)
            .get()
            .uri("/journey-service/compose")
            .retrieve()
            .body(String.class);

    assertThat(body).contains("aerotrace.risk.threshold-minutes").contains("kafka:9092");
  }
}
