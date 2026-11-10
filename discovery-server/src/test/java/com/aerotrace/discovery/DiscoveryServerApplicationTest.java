package com.aerotrace.discovery;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;

@SpringBootTest(
    webEnvironment = WebEnvironment.RANDOM_PORT,
    properties = {"spring.security.user.name=eureka", "spring.security.user.password=test-secret"})
class DiscoveryServerApplicationTest {

  private static final String VALID = basic("eureka", "test-secret");

  @LocalServerPort private int port;

  private static String basic(String user, String password) {
    return "Basic "
        + Base64.getEncoder()
            .encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));
  }

  private HttpResponse<String> send(String method, String path, String accept, String authorization)
      throws Exception {
    HttpRequest.Builder request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
            .header("Accept", accept)
            .method(method, HttpRequest.BodyPublishers.noBody());
    if (authorization != null) {
      request.header("Authorization", authorization);
    }
    return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> get(String path, String accept) throws Exception {
    return send("GET", path, accept, VALID);
  }

  @Test
  void healthEndpointIsUpWithoutCredentials() throws Exception {
    HttpResponse<String> response = send("GET", "/actuator/health", "application/json", null);

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

  @Test
  void registryReadsNeedCredentials() throws Exception {
    assertThat(send("GET", "/eureka/apps", "application/json", null).statusCode()).isEqualTo(401);
    assertThat(
            send("GET", "/eureka/apps", "application/json", basic("eureka", "wrong")).statusCode())
        .isEqualTo(401);
    assertThat(
            send("GET", "/eureka/apps", "application/json", basic("intruder", "test-secret"))
                .statusCode())
        .isEqualTo(401);
  }

  @Test
  void registrationNeedsCredentialsSoNobodyCanPlantAFakeService() throws Exception {
    // POST /eureka/apps/{app} is how an instance registers; an anonymous one is refused with 401
    // (not 403), i.e. authentication fails before CSRF could matter.
    HttpResponse<String> anonymous = send("POST", "/eureka/apps/EVIL", "application/json", null);

    assertThat(anonymous.statusCode()).isEqualTo(401);
  }

  @Test
  void dashboardNeedsCredentials() throws Exception {
    assertThat(send("GET", "/", "text/html", null).statusCode()).isEqualTo(401);
  }

  @Test
  void authenticatedClientsCanWriteWithoutACsrfToken() throws Exception {
    // Eureka clients are not browsers and cannot send CSRF tokens; with valid credentials the
    // request must reach Eureka (which rejects the empty body with a 4xx, never 401/403).
    int status = send("POST", "/eureka/apps/EMPTY", "application/json", VALID).statusCode();

    assertThat(status).isNotIn(401, 403);
  }
}
