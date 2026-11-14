package com.aerotrace.gateway.support;

import com.aerotrace.gateway.ratelimit.AuthRateLimiter;
import com.aerotrace.gateway.ratelimit.RateLimiter;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

/**
 * Test doubles for most gateway tests: the {@link TestRoutesConfig} routes plus an in-memory rate
 * limiter, so those tests need no Redis. Tests against a real Redis use {@link TestRoutesConfig}
 * alone.
 */
@TestConfiguration(proxyBeanMethods = false)
@Import(TestRoutesConfig.class)
public class GatewayTestConfig {

  public static final int ALLOWED_PER_KEY = 3;

  /**
   * Allows {@link #ALLOWED_PER_KEY} calls per token subject, then denies, like an exhausted token
   * bucket. Callers keyed by IP address (no token) get a large allowance: all anonymous test
   * requests share 127.0.0.1 and must not interfere with each other.
   */
  @Bean
  @Primary
  RateLimiter inMemoryRateLimiter() {
    Map<String, AtomicInteger> calls = new ConcurrentHashMap<>();
    return key -> {
      int limit = key.contains(".") || key.contains(":") ? 10_000 : ALLOWED_PER_KEY;
      int n = calls.computeIfAbsent(key, k -> new AtomicInteger()).incrementAndGet();
      return new RateLimiter.Decision(n <= limit, Math.max(0, limit - n));
    };
  }

  /** The authentication buckets never cut in here (they have their own Redis tests). */
  @Bean
  @Primary
  AuthRateLimiter unlimitedAuthRateLimiter() {
    return new AuthRateLimiter(key -> new RateLimiter.Decision(true, 100));
  }
}
