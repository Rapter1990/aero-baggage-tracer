package com.aerotrace.identity.user;

import com.aerotrace.common.security.Role;
import java.util.Set;
import java.util.UUID;

/**
 * A user as stored. The password hash and the passenger reference are kept out of {@link
 * #toString()} so an accidental log line cannot leak them.
 */
public record UserAccount(
    UUID id,
    String username,
    String passwordHash,
    String passengerRef,
    boolean enabled,
    Set<Role> roles) {

  public UserAccount {
    roles = Set.copyOf(roles);
  }

  @Override
  public String toString() {
    return "UserAccount[id=" + id + ", enabled=" + enabled + ", roles=" + roles + "]";
  }
}
