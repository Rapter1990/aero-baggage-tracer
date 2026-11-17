package com.aerotrace.recovery.base;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * A real PostgreSQL (see the parent), a real Redis with a password, and a capacity-service stand-in
 * on a real port. The client's read timeout is short in the {@code test} profile, so a slow
 * capacity-service is cut off quickly.
 */
public abstract class AbstractRecoveryTest extends AbstractTestContainerConfiguration {

  /** One stub for the whole JVM, so cached application contexts keep pointing at a live server. */
  protected static final CapacityStub CAPACITY = CapacityStub.start();

  protected static final String REDIS_PASSWORD = "test-redis-secret";

  private static final int REDIS_PORT = 6379;

  /** Started on first use and shared by every test class, like the PostgreSQL container. */
  private static final GenericContainer<?> REDIS =
      new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
          .withCommand("redis-server", "--requirepass", REDIS_PASSWORD)
          .withExposedPorts(REDIS_PORT);

  static {
    Runtime.getRuntime().addShutdownHook(new Thread(REDIS::stop));
  }

  /** Every test starts with a healthy capacity-service, whatever the test before it did to it. */
  @BeforeEach
  void healthyCapacity() {
    CAPACITY.reset();
  }

  private static void startRedis() {
    synchronized (REDIS) {
      if (!REDIS.isRunning()) {
        REDIS.start();
      }
    }
  }

  @DynamicPropertySource
  static void infrastructureProperties(DynamicPropertyRegistry registry) {
    registry.add("aerotrace.recovery.capacity.base-url", CAPACITY::baseUrl);
    registry.add(
        "spring.data.redis.host",
        () -> {
          startRedis();
          return REDIS.getHost();
        });
    registry.add(
        "spring.data.redis.port",
        () -> {
          startRedis();
          return REDIS.getMappedPort(REDIS_PORT);
        });
    registry.add("spring.data.redis.password", () -> REDIS_PASSWORD);
  }
}
