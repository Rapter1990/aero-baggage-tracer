package com.aerotrace.identity.policy;

import com.aerotrace.common.security.Role;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * What can be done on the platform and by which roles. This is the one place the answer lives:
 * services enforce it on their own endpoints and identity-service publishes it. Adding a permission
 * here, with its roles, is how a new rule is introduced.
 */
public enum Permission {
  /** Recovery cases move OPEN, ASSESSING, RESERVED, IN_TRANSIT, RESOLVED (backlog #11). */
  CASE_TRANSITION("Move a recovery case to its next state", Role.AGENT, Role.DISPATCHER),

  /** Capacity resources (backlog #12: admin-only resource management). */
  CAPACITY_MANAGE("Create and change capacity resources", Role.ADMIN),

  /** Risk thresholds and policies (backlog #11: ADMIN changes risk policy). */
  RISK_POLICY_CHANGE("Change risk thresholds and policies", Role.ADMIN),

  /** Identity-service itself. */
  SIGNING_KEY_ROTATE("Introduce a new token signing key", Role.ADMIN);

  private final String description;
  private final Set<Role> roles;

  Permission(String description, Role first, Role... others) {
    this.description = description;
    this.roles = Collections.unmodifiableSet(EnumSet.of(first, others));
  }

  public String description() {
    return description;
  }

  public Set<Role> roles() {
    return roles;
  }
}
