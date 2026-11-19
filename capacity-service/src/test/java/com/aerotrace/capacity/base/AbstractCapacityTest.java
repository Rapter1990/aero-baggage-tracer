package com.aerotrace.capacity.base;

import com.aerotrace.common.security.JwtClaims;
import com.aerotrace.common.security.Role;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** A real PostgreSQL (see the parent) plus helpers to act as a role. */
public abstract class AbstractCapacityTest extends AbstractTestContainerConfiguration {

  protected static RequestPostProcessor as(Role role) {
    return SecurityMockMvcRequestPostProcessors.jwt()
        .jwt(
            token -> {
              token.subject("user-" + role.name().toLowerCase(java.util.Locale.ROOT));
              token.claim(JwtClaims.ROLES, List.of(role.name()));
            })
        .authorities(new SimpleGrantedAuthority(role.authority()));
  }

  /** A leg id no other test uses, so tests may share the database. */
  protected static String newLeg() {
    return "LEG-" + UUID.randomUUID().toString().substring(0, 8);
  }
}
