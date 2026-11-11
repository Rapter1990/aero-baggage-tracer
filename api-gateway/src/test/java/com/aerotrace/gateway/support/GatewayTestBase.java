package com.aerotrace.gateway.support;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Boots the real gateway on a random port against the stub JWKS and downstream. */
@SpringBootTest(
    webEnvironment = WebEnvironment.RANDOM_PORT,
    properties = {
      "eureka.client.enabled=false",
      "spring.cloud.discovery.enabled=false",
      "aerotrace.gateway.issuer=" + TestJwks.ISSUER,
      "aerotrace.gateway.audience=" + TestJwks.AUDIENCE,
      "spring.data.redis.password=unused-in-tests",
      "management.health.redis.enabled=false",
      "management.otlp.metrics.export.enabled=false",
      "management.tracing.export.otlp.enabled=false"
    })
@Import(GatewayTestConfig.class)
public abstract class GatewayTestBase {

  private static final HttpClient HTTP = HttpClient.newHttpClient();
  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Value("${local.server.port}")
  private int port;

  @DynamicPropertySource
  static void jwks(DynamicPropertyRegistry registry) {
    registry.add("aerotrace.gateway.jwk-set-uri", TestJwks::uri);
  }

  protected HttpResponse<String> get(String path, String bearer, String... headers) {
    return send("GET", path, bearer, headers);
  }

  protected HttpResponse<String> send(
      String method, String path, String bearer, String... headers) {
    HttpRequest.Builder request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
            .method(method, HttpRequest.BodyPublishers.noBody());
    if (bearer != null) {
      request.header("Authorization", "Bearer " + bearer);
    }
    for (int i = 0; i + 1 < headers.length; i += 2) {
      request.header(headers[i], headers[i + 1]);
    }
    try {
      return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
    } catch (IOException e) {
      throw new IllegalStateException(e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }

  protected static JsonNode json(HttpResponse<String> response) {
    return JSON.readTree(response.body());
  }
}
