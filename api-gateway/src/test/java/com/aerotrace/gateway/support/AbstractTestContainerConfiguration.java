package com.aerotrace.gateway.support;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.junit.jupiter.api.BeforeAll;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Starts a real, password-protected Redis in Docker for integration tests and points Spring's
 * {@code spring.data.redis.*} properties at it. Tests are skipped when Docker is not available.
 *
 * <p>Extend it from a plain JUnit test (use {@link #redisHost()}, {@link #redisPort()} and {@link
 * #REDIS_PASSWORD} to connect) or from a {@code @SpringBootTest} (the properties are overridden
 * automatically).
 *
 * <p>The container is started once per JVM and shared by every subclass; it is not stopped after
 * each test class. Spring caches application contexts across classes, and a restarted container
 * would get a new mapped port that a cached context would not know about. It is stopped when the
 * JVM exits (and Testcontainers' reaper removes it even if the JVM dies).
 */
public abstract class AbstractTestContainerConfiguration {

  private static final Logger log =
      LoggerFactory.getLogger(AbstractTestContainerConfiguration.class);

  private static final String REDIS_IMAGE = "redis:7-alpine";
  private static final int REDIS_PORT = 6379;

  /** The password Redis is started with ({@code requirepass}). */
  protected static final String REDIS_PASSWORD = "test-redis-secret";

  private static final GenericContainer<?> REDIS_CONTAINER =
      new GenericContainer<>(DockerImageName.parse(REDIS_IMAGE))
          .withCommand("redis-server", "--requirepass", REDIS_PASSWORD)
          .withExposedPorts(REDIS_PORT);

  static {
    Runtime.getRuntime().addShutdownHook(new Thread(REDIS_CONTAINER::stop));
  }

  @BeforeAll
  static void startContainer() {
    assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker is not available");
    synchronized (REDIS_CONTAINER) {
      if (!REDIS_CONTAINER.isRunning()) {
        log.info("Starting Redis Testcontainer...");
        REDIS_CONTAINER.start();
        log.info("Redis Testcontainer started on port: {}", redisPort());
      }
    }
  }

  @DynamicPropertySource
  static void overrideProps(DynamicPropertyRegistry registry) {
    // Point the gateway's Redis connection at the Testcontainers Redis.
    registry.add("spring.data.redis.host", REDIS_CONTAINER::getHost);
    registry.add("spring.data.redis.port", AbstractTestContainerConfiguration::redisPort);
    registry.add("spring.data.redis.password", () -> REDIS_PASSWORD);
  }

  protected static String redisHost() {
    return REDIS_CONTAINER.getHost();
  }

  protected static int redisPort() {
    return REDIS_CONTAINER.getMappedPort(REDIS_PORT);
  }
}
