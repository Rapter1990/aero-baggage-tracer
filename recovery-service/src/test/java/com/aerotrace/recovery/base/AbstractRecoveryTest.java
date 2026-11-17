package com.aerotrace.recovery.base;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * A real PostgreSQL (see the parent) and a capacity-service stand-in on a real port. The client's
 * read timeout is short in the {@code test} profile, so a slow capacity-service is cut off quickly.
 */
public abstract class AbstractRecoveryTest extends AbstractTestContainerConfiguration {

  /** One stub for the whole JVM, so cached application contexts keep pointing at a live server. */
  protected static final CapacityStub CAPACITY = CapacityStub.start();

  /** Every test starts with a healthy capacity-service, whatever the test before it did to it. */
  @BeforeEach
  void healthyCapacity() {
    CAPACITY.reset();
  }

  @DynamicPropertySource
  static void capacityProperties(DynamicPropertyRegistry registry) {
    registry.add("aerotrace.recovery.capacity.base-url", CAPACITY::baseUrl);
  }
}
