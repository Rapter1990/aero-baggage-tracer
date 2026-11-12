package com.aerotrace.gateway.ratelimit;

import com.aerotrace.common.error.ErrorCode;
import com.aerotrace.gateway.problem.ProblemResponseWriter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Applies the rate limiter to API calls and answers 429 as a ProblemDetail. Callers are keyed by
 * token subject, or by remote address when there is no authenticated token (e.g. token requests).
 * Not a Spring bean: the security chain adds it right after bearer-token authentication.
 */
public class RateLimitFilter extends OncePerRequestFilter {

  private final RateLimiter limiter;
  private final ProblemResponseWriter problems;

  public RateLimitFilter(RateLimiter limiter, ProblemResponseWriter problems) {
    this.limiter = limiter;
    this.problems = problems;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return !request.getRequestURI().startsWith("/api/v1/");
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    RateLimiter.Decision decision = limiter.tryAcquire(callerKey(request));
    if (decision.remaining() >= 0) {
      response.setHeader("X-RateLimit-Remaining", String.valueOf(decision.remaining()));
    }
    if (decision.allowed()) {
      chain.doFilter(request, response);
      return;
    }
    response.setHeader("Retry-After", "1");
    problems.write(response, ErrorCode.RATE_LIMITED, "Too many requests, retry later");
  }

  private static String callerKey(HttpServletRequest request) {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication != null
        && authentication.isAuthenticated()
        && !(authentication instanceof AnonymousAuthenticationToken)
        && authentication.getName() != null) {
      return authentication.getName();
    }
    String remote = request.getRemoteAddr();
    return remote == null ? "unknown" : remote;
  }
}
