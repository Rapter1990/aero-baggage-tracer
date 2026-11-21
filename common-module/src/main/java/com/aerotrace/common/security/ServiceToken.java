package com.aerotrace.common.security;

import java.util.Set;
import org.springframework.core.env.Environment;

/**
 * The shared secret one service presents to another when no user token exists (the search-service's
 * rebuild reading the internal export endpoints). Sent in {@link #HEADER}; never logged, never
 * routed by the gateway (it strips the header and has no route to {@code /internal/**}).
 */
public final class ServiceToken {

  /** The header the secret travels in (the same one the capacity-service accepts). */
  public static final String HEADER = "X-Service-Token";

  /** The role a call with the right secret gets ({@code ROLE_SERVICE}). */
  public static final String ROLE = "SERVICE";

  /** The principal name of such a call. */
  public static final String PRINCIPAL = "service";

  /** A shorter secret is treated as not set: it would be too easy to guess. */
  public static final int MIN_SECRET_LENGTH = 16;

  /**
   * The only profiles in which an unset secret opens the endpoint, for local runs where the caller
   * sends none. {@code test} is not one of them: tests must see the closed behaviour.
   */
  public static final Set<String> OPEN_WITHOUT_TOKEN_PROFILES = Set.of("local", "dev");

  /** How the endpoints behind the token behave. */
  public enum Mode {
    /** A secret is configured: only a call presenting it gets in. */
    REQUIRED,
    /** No usable secret, outside the development profiles: nothing gets in (fail closed). */
    CLOSED,
    /** No secret in a development profile: every call gets in (local runs only). */
    OPEN
  }

  private ServiceToken() {}

  /** True when the value is long enough to be used as the secret. */
  public static boolean usable(String token) {
    return token != null && !token.isBlank() && token.length() >= MIN_SECRET_LENGTH;
  }

  public static Mode mode(String token, Environment environment) {
    if (usable(token)) {
      return Mode.REQUIRED;
    }
    for (String profile : environment.getActiveProfiles()) {
      if (OPEN_WITHOUT_TOKEN_PROFILES.contains(profile)) {
        return Mode.OPEN;
      }
    }
    return Mode.CLOSED;
  }
}
