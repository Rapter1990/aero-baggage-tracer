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
 *   <li>a signed-in user: the subject of the token (the name of a JWT authentication). The
 *       identity-service issues the user's id as the subject, so the value is the opaque user id (a
 *       UUID), not the username: it stays valid when a username changes, and it names the account
 *       in the identity-service;
 *   <li>a service that proved itself with the service token: {@link #SERVICE};
 *   <li>a request with no credentials (self-registration, login): {@link #ANONYMOUS};
 *   <li>no request at all (a scheduled job, a Kafka consumer): {@link #SYSTEM}.
 * </ul>
 *
 * The value is cut to the 100 characters of the audit columns and is never a token or a secret. It
 * is read from the thread's security context, so work handed to another thread (an async call) must
 * carry the context along (for example with {@code DelegatingSecurityContextExecutor}) or it is
 * recorded as {@link #SYSTEM}.
 */
public class SecurityAuditorAware implements AuditorAware<String> {

  /** Longest value an audit column holds. */
  public static final int MAX_LENGTH = 100;

  public static final String SYSTEM = "system";
  public static final String ANONYMOUS = "anonymous";

  /** A service caller that proved itself with the service token (the recovery-service sweeper). */
  public static final String SERVICE = "service";

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
    // Cut by characters, never in the middle of a surrogate pair.
    return name.codePointCount(0, name.length()) > MAX_LENGTH
        ? name.substring(0, name.offsetByCodePoints(0, MAX_LENGTH))
        : name;
  }

  @Override
  public Optional<String> getCurrentAuditor() {
    return Optional.of(currentAuditor());
  }
}
