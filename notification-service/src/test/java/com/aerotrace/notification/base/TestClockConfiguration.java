package com.aerotrace.notification.base;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Replaces the application clock with one the test controls (no sleeps, no wall-clock reads). It is
 * a {@code Clock} bean marked primary, so every component that injects a {@code Clock} gets it.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestClockConfiguration {

  @Bean
  @Primary
  MutableClock testClock() {
    return new MutableClock();
  }
}
