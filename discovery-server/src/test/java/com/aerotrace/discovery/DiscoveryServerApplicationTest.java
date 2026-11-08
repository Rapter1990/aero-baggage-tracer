package com.aerotrace.discovery;

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
class DiscoveryServerApplicationTest {

  @LocalServerPort private int port;

  private HttpResponse<String> get(String path, String accept) throws Exception {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
            .header("Accept", accept)
            .build();
    return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void healthEndpointIsUp() throws Exception {
    HttpResponse<String> response = get("/actuator/health", "application/json");

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.body()).contains("\"status\":\"UP\"");
  }

  @Test
  void registryStartsEmptyAndAcceptsReads() throws Exception {
    assertThat(get("/eureka/apps", "application/json").statusCode()).isEqualTo(200);
  }

  @Test
  void dashboardIsServed() throws Exception {
    assertThat(get("/", "text/html").statusCode()).isEqualTo(200);
  }
}
