package com.aerotrace.identity.policy;

import com.aerotrace.common.security.Role;
import java.util.Collection;
import org.springframework.stereotype.Component;

/** Answers "may these roles do this?" from the {@link Permission} catalog. Fails closed. */
@Component
public class AuthorizationPolicy {

  public boolean isAllowed(Permission permission, Collection<Role> roles) {
    if (permission == null || roles == null) {
      return false;
    }
    return roles.stream().anyMatch(permission.roles()::contains);
  }
}
