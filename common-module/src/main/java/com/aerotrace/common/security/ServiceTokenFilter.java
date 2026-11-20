package com.aerotrace.common.security;

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
import org.springframework.core.env.Environment;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authenticates a service caller by the shared secret in {@link ServiceToken#HEADER}: a match gives
 * the call {@code ROLE_SERVICE}; a wrong or missing secret changes nothing, so the security rules
 * answer 401. The secret is compared in constant time and never logged.
 *
 * <p>Fail closed: with no usable secret configured nothing is accepted, except in the {@code local}
 * and {@code dev} profiles ({@link ServiceToken.Mode#OPEN}), where every call is let in so a local
 * run works without one. Both are said once at start-up. Meant for a security filter chain of its
 * own (for example {@code /internal/**}) that accepts no user token.
 */
public final class ServiceTokenFilter extends OncePerRequestFilter {

  private static final Logger LOG = LoggerFactory.getLogger(ServiceTokenFilter.class);

  private final byte[] secret;
  private final ServiceToken.Mode mode;

  public ServiceTokenFilter(String token, Environment environment) {
    this.mode = ServiceToken.mode(token, environment);
    this.secret =
        mode == ServiceToken.Mode.REQUIRED ? token.getBytes(StandardCharsets.UTF_8) : new byte[0];
    if (mode == ServiceToken.Mode.CLOSED) {
      LOG.warn(
          "No service token of at least {} characters is configured: the internal endpoints refuse"
              + " every call",
          ServiceToken.MIN_SECRET_LENGTH);
    } else if (mode == ServiceToken.Mode.OPEN) {
      LOG.warn(
          "No service token is configured and a development profile is active: the internal"
              + " endpoints accept every call. Never run like this outside a local machine");
    }
  }

  public ServiceToken.Mode mode() {
    return mode;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    if (mode == ServiceToken.Mode.OPEN || matches(request.getHeader(ServiceToken.HEADER))) {
      SecurityContextHolder.getContext()
          .setAuthentication(
              UsernamePasswordAuthenticationToken.authenticated(
                  ServiceToken.PRINCIPAL,
                  null,
                  List.of(new SimpleGrantedAuthority("ROLE_" + ServiceToken.ROLE))));
    }
    chain.doFilter(request, response);
  }

  private boolean matches(String presented) {
    return mode == ServiceToken.Mode.REQUIRED
        && presented != null
        && MessageDigest.isEqual(secret, presented.getBytes(StandardCharsets.UTF_8));
  }
}
