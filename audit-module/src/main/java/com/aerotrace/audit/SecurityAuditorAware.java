package com.aerotrace.audit;

import java.util.Optional;
import org.springframework.data.domain.AuditorAware;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Who is acting, for the audit columns:
 *
 * <ul>
 *   <li>a signed-in user: the subject of the token (the name of a JWT authentication);
 *   <li>a service that proved itself with the service token: {@code service};
 *   <li>a request with no credentials (self-registration, login): {@code anonymous};
 *   <li>no request at all (a scheduled job, a Kafka consumer): {@code system}.
 * </ul>
 *
 * The value is cut to the 100 characters of the audit columns and is never a token or a secret.
 */
public class SecurityAuditorAware implements AuditorAware<String> {

  /** Longest value an audit column holds. */
  public static final int MAX_LENGTH = 100;

  public static final String SYSTEM = "system";
  public static final String ANONYMOUS = "anonymous";

  /** The acting user right now; usable by code that writes audit columns in its own SQL. */
  public static String currentAuditor() {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication == null) {
      return SYSTEM;
    }
    if (authentication instanceof AnonymousAuthenticationToken
        || !authentication.isAuthenticated()) {
      return ANONYMOUS;
    }
    String name = authentication.getName();
    if (name == null || name.isBlank()) {
      return SYSTEM;
    }
    return name.length() > MAX_LENGTH ? name.substring(0, MAX_LENGTH) : name;
  }

  @Override
  public Optional<String> getCurrentAuditor() {
    return Optional.of(currentAuditor());
  }
}
