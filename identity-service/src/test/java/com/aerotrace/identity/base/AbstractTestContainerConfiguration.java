package com.aerotrace.identity.base;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.junit.jupiter.api.BeforeAll;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * A real PostgreSQL in Docker for every Spring test. The other test defaults (no Eureka, no
 * telemetry export, a cheap BCrypt cost, a small connection pool) are in the {@code test} profile,
 * {@code application-test.yml}, so a single test can still override them.
 *
 * <p>The container is started once per JVM and shared by every subclass. A container per class
 * ({@code @Container} on a static field) would get a new mapped port for each class, which Spring's
 * cached application contexts would not know about. Tests therefore must not assume an empty
 * database. Tests are skipped when Docker is not available, except in CI, where that is a failure.
 */
@ActiveProfiles("test")
public abstract class AbstractTestContainerConfiguration {

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
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
  }
}
