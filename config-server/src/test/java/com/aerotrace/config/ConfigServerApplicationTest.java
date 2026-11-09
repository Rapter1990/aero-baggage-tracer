package com.aerotrace.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class ConfigServerApplicationTest {

  @LocalServerPort private int port;

  private HttpResponse<String> get(String path) throws Exception {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
            .header("Accept", "application/json")
            .build();
    return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void healthEndpointIsUp() throws Exception {
    HttpResponse<String> response = get("/actuator/health");

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.body()).contains("\"status\":\"UP\"");
  }

  @Test
  void servesSharedRiskThresholdForAnyService() throws Exception {
    HttpResponse<String> response = get("/risk-streams-service/dev");

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.body()).contains("aerotrace.risk.threshold-minutes");
  }

  @Test
  void composeProfileExternalizesKafkaEndpoint() throws Exception {
    HttpResponse<String> response = get("/journey-service/compose");

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.body()).contains("kafka:9092");
  }

  @Test
  void k8sProfileIsServed() throws Exception {
    assertThat(get("/journey-service/k8s").body()).contains("aerotrace.kafka.bootstrap-servers");
  }
}
