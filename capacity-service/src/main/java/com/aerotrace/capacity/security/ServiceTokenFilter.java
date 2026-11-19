package com.aerotrace.capacity.security;

import com.aerotrace.audit.SecurityAuditorAware;
import com.aerotrace.capacity.constant.CapacityRules;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Lets a service call without a user token. The recovery-service sweeper releases holds long after
 * the user's token has expired, so it sends the shared secret in {@code X-Service-Token}. A match
 * gives the call the SERVICE role, which the security rules allow only on release and confirmation;
 * a wrong or missing secret changes nothing, so the usual token rules apply. The secret is compared
 * in constant time and never logged.
 */
final class ServiceTokenFilter extends OncePerRequestFilter {

  private static final Logger LOG = LoggerFactory.getLogger(ServiceTokenFilter.class);

  /** A shorter secret is treated as not set: it would be too easy to guess. */
  static final int MIN_SECRET_LENGTH = 16;

  private final byte[] secret;

  ServiceTokenFilter(String token) {
    boolean tooShort = token != null && !token.isBlank() && token.length() < MIN_SECRET_LENGTH;
    if (tooShort) {
      // Said once, at start-up: the recovery-service would keep sending its token, every release
      // and
      // confirmation would answer 401, and holds of active cases would expire. The value is not
      // logged.
      LOG.warn(
          "CAPACITY_SERVICE_TOKEN is shorter than {} characters and is ignored: no service caller"
              + " (the recovery-service sweeper) can release or confirm holds",
          MIN_SECRET_LENGTH);
    }
    this.secret =
        token == null || token.length() < MIN_SECRET_LENGTH
            ? new byte[0]
            : token.getBytes(StandardCharsets.UTF_8);
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String presented = request.getHeader(CapacityRules.SERVICE_TOKEN_HEADER);
    if (secret.length > 0
        && presented != null
        && MessageDigest.isEqual(secret, presented.getBytes(StandardCharsets.UTF_8))) {
      SecurityContextHolder.getContext()
          .setAuthentication(
              UsernamePasswordAuthenticationToken.authenticated(
                  SecurityAuditorAware.SERVICE,
                  null,
                  List.of(new SimpleGrantedAuthority("ROLE_" + CapacityRules.SERVICE_ROLE))));
    }
    chain.doFilter(request, response);
  }
}
