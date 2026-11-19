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

  private final byte[] secret;

  /** A shorter secret is treated as not set: it would be too easy to guess. */
  static final int MIN_SECRET_LENGTH = 16;

  ServiceTokenFilter(String token) {
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
