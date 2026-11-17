package com.aerotrace.recovery.base;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.junit.jupiter.api.BeforeAll;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * A real PostgreSQL in Docker for every Spring test. The container is started once per JVM and
 * shared by every subclass, so tests must not assume an empty database. Tests are skipped when
 * Docker is not available, except in CI, where that is a failure.
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
        throw new AssertionError("Docker is required to run the recovery-service tests in CI");
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
