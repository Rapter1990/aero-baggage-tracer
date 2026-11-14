package com.aerotrace.gateway.ratelimit;

/**
 * The stricter bucket for the authentication endpoints, one per endpoint and client address. It is
 * a wrapper and not a second {@link RateLimiter} bean, so the general limiter stays the only bean
 * of that type.
 */
public record AuthRateLimiter(RateLimiter delegate) {

  public RateLimiter.Decision tryAcquire(String key) {
    return delegate.tryAcquire(key);
  }
}
