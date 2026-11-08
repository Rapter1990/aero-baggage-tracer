package com.aerotrace.common.security;

import com.aerotrace.common.error.ApiException;
import com.aerotrace.common.error.ErrorCode;
import java.util.Collection;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;

/** Read-only access to the authenticated caller. Empty results mean "not a JWT caller". */
public final class SecurityContextHelper {

  private SecurityContextHelper() {}

  private static Optional<Jwt> jwt() {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    if (auth != null && auth.getPrincipal() instanceof Jwt jwt) {
      return Optional.of(jwt);
    }
    return Optional.empty();
  }

  public static Optional<String> currentSubject() {
    return jwt().map(Jwt::getSubject);
  }

  public static Set<Role> currentRoles() {
    Set<Role> roles = EnumSet.noneOf(Role.class);
    jwt()
        .map(j -> j.getClaim(JwtClaims.ROLES))
        .filter(Collection.class::isInstance)
        .ifPresent(
            claim -> {
              for (Object value : (Collection<?>) claim) {
                Role.fromClaim(String.valueOf(value)).ifPresent(roles::add);
              }
            });
    return roles;
  }

  public static boolean hasRole(Role role) {
    return currentRoles().contains(role);
  }

  public static Optional<String> currentPassengerRef() {
    // Fail closed: only a non-blank string claim counts, so ownership filters never match "".
    return jwt()
        .map(j -> j.getClaim(JwtClaims.PASSENGER_REF))
        .filter(String.class::isInstance)
        .map(String.class::cast)
        .filter(s -> !s.isBlank());
  }

  /** Passenger ref for row-level ownership checks; denies access when it is absent. */
  public static String requirePassengerRef() {
    return currentPassengerRef()
        .orElseThrow(
            () -> new ApiException(ErrorCode.ACCESS_DENIED, "No passenger identity on the token."));
  }
}
