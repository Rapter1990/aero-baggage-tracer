package com.aerotrace.identity.support;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;

/** Boots the real identity-service on a random port with telemetry export and Eureka off. */
@SpringBootTest(
    webEnvironment = WebEnvironment.RANDOM_PORT,
    properties = {
      "eureka.client.enabled=false",
      "spring.cloud.discovery.enabled=false",
      "management.otlp.metrics.export.enabled=false",
      "management.tracing.export.otlp.enabled=false",
      "management.logging.export.otlp.enabled=false"
    })
public abstract class IdentityTestBase {

  @Value("${local.server.port}")
  private int port;

  protected HttpResponse<String> get(String path) {
    try {
      return HttpClient.newHttpClient()
          .send(
              HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
              HttpResponse.BodyHandlers.ofString());
    } catch (IOException e) {
      throw new IllegalStateException(e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }
}
