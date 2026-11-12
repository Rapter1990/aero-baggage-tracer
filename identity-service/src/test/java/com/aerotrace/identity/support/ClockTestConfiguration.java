package com.aerotrace.identity.support;

import java.time.Instant;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** Replaces the system clock with a {@link MutableClock} that the test controls. */
@TestConfiguration(proxyBeanMethods = false)
public class ClockTestConfiguration {

  @Bean
  @Primary
  MutableClock testClock() {
    return new MutableClock(Instant.parse("2030-01-01T00:00:00Z"));
  }
}
