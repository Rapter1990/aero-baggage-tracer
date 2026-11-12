package com.aerotrace.identity.support;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.BeforeAll;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Boots the real identity-service on a random port against a real PostgreSQL in Docker, with
 * telemetry export and Eureka off. Tests are skipped when Docker is not available.
 *
 * <p>The database container is started once per JVM and shared by every subclass: Spring caches
 * application contexts across classes, and a restarted container would get a new mapped port that a
 * cached context would not know about. Tests therefore must not assume an empty database.
 */
@SpringBootTest(
    webEnvironment = WebEnvironment.RANDOM_PORT,
    properties = {
      "eureka.client.enabled=false",
      "spring.cloud.discovery.enabled=false",
      "management.otlp.metrics.export.enabled=false",
      "management.tracing.export.otlp.enabled=false",
      "management.logging.export.otlp.enabled=false",
      // A low BCrypt cost keeps the tests fast; production uses the default.
      "aerotrace.identity.bcrypt-strength=4",
      // Test-only key-encryption key (bytes 0..31).
      "aerotrace.identity.keys.encryption-key=AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8="
    })
public abstract class IdentityTestBase {

  private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18-alpine");

  static {
    Runtime.getRuntime().addShutdownHook(new Thread(POSTGRES::stop));
  }

  @BeforeAll
  static void startDatabase() {
    assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker is not available");
    synchronized (POSTGRES) {
      if (!POSTGRES.isRunning()) {
        POSTGRES.start();
      }
    }
  }

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
  }

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
