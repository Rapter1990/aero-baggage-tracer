package com.aerotrace.gateway.ratelimit;

import com.aerotrace.common.error.ApiErrorWriter;
import com.aerotrace.common.error.ErrorCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Applies the rate limiters to API calls and answers 429 as an ApiError.
 *
 * <ul>
 *   <li>The authentication endpoints ({@code /api/v1/auth/*}) first pass a stricter bucket of their
 *       own, one per endpoint and client address: they are where passwords are guessed and accounts
 *       are made.
 *   <li>Every API call then passes the general bucket, keyed by token subject, or by client address
 *       when there is no authenticated token.
 * </ul>
 *
 * The client address is the peer, or, behind a trusted proxy, the address that proxy reports (see
 * {@link ClientAddressResolver}). Not a Spring bean: the security chain adds it right after
 * bearer-token authentication.
 */
public class RateLimitFilter extends OncePerRequestFilter {

  private static final String API_PREFIX = "/api/v1/";
  private static final String AUTH_PREFIX = "/api/v1/auth/";

  /** Each has its own bucket; any other path under auth shares {@link #OTHER_ENDPOINT}. */
  private static final Set<String> AUTH_ENDPOINTS =
      Set.of("login", "register", "refresh-token", "forgot-password", "reset-password", "logout");

  private static final String OTHER_ENDPOINT = "other";

  private final RateLimiter limiter;
  private final AuthRateLimiter authLimiter;
  private final ClientAddressResolver addresses;
  private final ApiErrorWriter errors;

  public RateLimitFilter(
      RateLimiter limiter,
      AuthRateLimiter authLimiter,
      ClientAddressResolver addresses,
      ApiErrorWriter errors) {
    this.limiter = limiter;
    this.authLimiter = authLimiter;
    this.addresses = addresses;
    this.errors = errors;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return !request.getRequestURI().startsWith(API_PREFIX);
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String address = addresses.resolve(request);

    String endpoint = authEndpoint(request.getRequestURI());
    if (endpoint != null) {
      RateLimiter.Decision auth = authLimiter.tryAcquire("auth:" + endpoint + ":" + address);
      if (!auth.allowed()) {
        refuse(request, response, auth);
        return;
      }
    }

    RateLimiter.Decision decision = limiter.tryAcquire(callerKey(address));
    if (decision.remaining() >= 0) {
      response.setHeader("X-RateLimit-Remaining", String.valueOf(decision.remaining()));
    }
    if (decision.allowed()) {
      chain.doFilter(request, response);
      return;
    }
    refuse(request, response, decision);
  }

  private void refuse(
      HttpServletRequest request, HttpServletResponse response, RateLimiter.Decision decision)
      throws IOException {
    response.setHeader("Retry-After", String.valueOf(Math.max(1, decision.retryAfterSeconds())));
    errors.write(request, response, ErrorCode.RATE_LIMITED, "Too many requests, retry later");
  }

  /** The name of the authentication endpoint being called, or null for any other path. */
  private static String authEndpoint(String uri) {
    if (!uri.startsWith(AUTH_PREFIX)) {
      return null;
    }
    String name = uri.substring(AUTH_PREFIX.length());
    return AUTH_ENDPOINTS.contains(name) ? name : OTHER_ENDPOINT;
  }

  private static String callerKey(String address) {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication != null
        && authentication.isAuthenticated()
        && !(authentication instanceof AnonymousAuthenticationToken)
        && authentication.getName() != null) {
      return authentication.getName();
    }
    return address;
  }
}
