package com.aerotrace.common.security;

import java.util.Locale;
import java.util.Optional;

/** Platform roles (spec section 11). */
public enum Role {
  PASSENGER,
  AGENT,
  DISPATCHER,
  ADMIN;

  public String authority() {
    return "ROLE_" + name();
  }

  /** Parses a JWT role claim value ("agent", "ROLE_AGENT"); unknown values yield empty. */
  public static Optional<Role> fromClaim(String value) {
    if (value == null) {
      return Optional.empty();
    }
    String normalized = value.trim().toUpperCase(Locale.ROOT);
    if (normalized.startsWith("ROLE_")) {
      normalized = normalized.substring(5);
    }
    for (Role role : values()) {
      if (role.name().equals(normalized)) {
        return Optional.of(role);
      }
    }
    return Optional.empty();
  }
}
