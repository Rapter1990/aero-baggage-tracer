package com.aerotrace.identity.seed;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Switches test-user seeding on. Off unless asked for, and the password has no default: seeding
 * refuses to run without one rather than create accounts with a well-known password.
 */
@ConfigurationProperties("aerotrace.identity.seed")
public record SeedProperties(boolean enabled, String password) {

  @Override
  public String toString() {
    return "SeedProperties[enabled=" + enabled + "]";
  }
}
