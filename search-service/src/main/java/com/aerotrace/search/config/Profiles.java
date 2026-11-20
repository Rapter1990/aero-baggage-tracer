package com.aerotrace.search.config;

import java.util.Set;
import org.springframework.core.env.Environment;

/**
 * Which runs may fall back to development defaults (a bundled public key, a random cursor key, no
 * export token): only those with an active {@code local}, {@code dev} or {@code test} profile.
 * Anything else (Compose, Kubernetes, a plain {@code java -jar}) must be configured, or the service
 * refuses to start.
 */
public final class Profiles {

  /** The profiles that allow development defaults. */
  // Not the list of the owners' ServiceToken (local, dev only): there "test" stays closed on
  // purpose.
  public static final Set<String> DEVELOPMENT = Set.of("local", "dev", "test");

  private Profiles() {}

  public static boolean development(Environment environment) {
    for (String profile : environment.getActiveProfiles()) {
      if (DEVELOPMENT.contains(profile)) {
        return true;
      }
    }
    return false;
  }
}
