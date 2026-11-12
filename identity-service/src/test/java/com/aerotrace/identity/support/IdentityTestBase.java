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
      "aerotrace.identity.keys.encryption-key=AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=",
      "aerotrace.identity.token.issuer=http://identity.test",
      // Every cached context would otherwise run its own key maintenance on the shared database.
      "aerotrace.identity.keys.maintenance-interval=PT24H",
      // Spring caches one context per distinct test configuration, each with its own pool; keep
      // the pools small so many cached contexts fit within the database's connection limit.
      "spring.datasource.hikari.maximum-pool-size=6",
      "spring.datasource.hikari.minimum-idle=0"
    })
public abstract class IdentityTestBase {

  private static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer("postgres:18-alpine")
          .withCommand("postgres", "-c", "max_connections=300");

  static {
    Runtime.getRuntime().addShutdownHook(new Thread(POSTGRES::stop));
  }

  @BeforeAll
  static void startDatabase() {
    boolean docker = DockerClientFactory.instance().isDockerAvailable();
    if (System.getenv("CI") != null) {
      // A build server without Docker must fail, not quietly skip the integration tests.
      if (!docker) {
        throw new AssertionError("Docker is required to run the identity-service tests in CI");
      }
    } else {
      assumeTrue(docker, "Docker is not available");
    }
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

  protected int port() {
    return port;
  }

  protected HttpResponse<String> post(String path, String contentType, String body) {
    try {
      return HttpClient.newHttpClient()
          .send(
              HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                  .header("Content-Type", contentType)
                  .POST(HttpRequest.BodyPublishers.ofString(body))
                  .build(),
              HttpResponse.BodyHandlers.ofString());
    } catch (IOException e) {
      throw new IllegalStateException(e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }

  /** Any method, with an optional JSON body and bearer token. */
  protected HttpResponse<String> send(String method, String path, String body, String bearer) {
    try {
      HttpRequest.Builder request =
          HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
              .method(
                  method,
                  body == null
                      ? HttpRequest.BodyPublishers.noBody()
                      : HttpRequest.BodyPublishers.ofString(body));
      if (body != null) {
        request.header("Content-Type", "application/json");
      }
      if (bearer != null) {
        request.header("Authorization", "Bearer " + bearer);
      }
      return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
    } catch (IOException e) {
      throw new IllegalStateException(e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }

  protected HttpResponse<String> postJson(String path, String json) {
    return post(path, "application/json", json);
  }

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
