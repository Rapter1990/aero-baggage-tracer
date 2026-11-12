package com.aerotrace.identity.policy;

import com.aerotrace.common.security.Role;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import org.springframework.stereotype.Component;

/** Answers "may these roles do this?" from the {@link Permission} catalog. Fails closed. */
@Component
public class AuthorizationPolicy {

  /** One catalog line as published: roles are sorted so the output is stable. */
  public record Entry(String name, String description, List<String> roles) {}

  public boolean isAllowed(Permission permission, Collection<Role> roles) {
    if (permission == null || roles == null) {
      return false;
    }
    return roles.stream().anyMatch(permission.roles()::contains);
  }

  public List<Entry> catalog() {
    return Arrays.stream(Permission.values())
        .map(
            permission ->
                new Entry(
                    permission.name(),
                    permission.description(),
                    permission.roles().stream().map(Role::name).sorted().toList()))
        .toList();
  }
}
