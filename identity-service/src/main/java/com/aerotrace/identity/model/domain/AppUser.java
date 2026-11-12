package com.aerotrace.identity.model.domain;

import com.aerotrace.common.security.Role;
import java.util.Set;
import java.util.UUID;

/**
 * A user as the rest of the service sees it, detached from persistence. The password hash and the
 * passenger reference are kept out of {@link #toString()} so an accidental log line cannot leak
 * them.
 */
public record AppUser(
    UUID id,
    String username,
    String passwordHash,
    String passengerRef,
    boolean enabled,
    Set<Role> roles) {

  public AppUser {
    roles = Set.copyOf(roles);
  }

  @Override
  public String toString() {
    return "AppUser[id=" + id + ", enabled=" + enabled + ", roles=" + roles + "]";
  }
}
