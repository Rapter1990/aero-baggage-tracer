package com.aerotrace.gateway.support;

import static org.springframework.cloud.gateway.server.mvc.filter.BeforeFilterFunctions.uri;
import static org.springframework.cloud.gateway.server.mvc.handler.GatewayRouterFunctions.route;
import static org.springframework.cloud.gateway.server.mvc.handler.HandlerFunctions.http;

import com.aerotrace.gateway.ratelimit.RateLimiter;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.web.servlet.function.RequestPredicates;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerResponse;

/**
 * Test doubles for what does not exist yet: routes that proxy to {@link TestDownstream} over real
 * HTTP, and an in-memory rate limiter for tests that do not need Redis.
 */
@TestConfiguration(proxyBeanMethods = false)
public class GatewayTestConfig {

  public static final int ALLOWED_PER_KEY = 3;

  @Bean
  RouterFunction<ServerResponse> testEchoRoute() {
    return route("test-echo")
        .route(RequestPredicates.path("/api/v1/echo/**"), http())
        .before(uri(TestDownstream.uri()))
        .build();
  }

  @Bean
  RouterFunction<ServerResponse> testAdminRoute() {
    return route("test-admin")
        .route(RequestPredicates.path("/api/v1/admin/**"), http())
        .before(uri(TestDownstream.uri()))
        .build();
  }

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
}
