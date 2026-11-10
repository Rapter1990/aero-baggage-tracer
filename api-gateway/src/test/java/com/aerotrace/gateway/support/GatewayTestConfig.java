package com.aerotrace.gateway.support;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.cloud.gateway.filter.ratelimit.RateLimiter;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatchers;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

/**
 * Test doubles for the parts of the platform that do not exist yet: a downstream service that
 * echoes what it received, and an in-memory rate limiter in place of Redis.
 */
@TestConfiguration(proxyBeanMethods = false)
public class GatewayTestConfig {

  public static final int ALLOWED_PER_KEY = 3;

  /** Routes that end in the in-process echo endpoint instead of a real downstream service. */
  @Bean
  RouteLocator testRoutes(RouteLocatorBuilder builder) {
    return builder
        .routes()
        .route("test-echo", r -> r.path("/api/v1/echo/**").uri("forward:/stub/echo"))
        .route("test-admin", r -> r.path("/api/v1/admin/**").uri("forward:/stub/echo"))
        .build();
  }

  @Bean
  RouterFunction<ServerResponse> echoEndpoint() {
    return RouterFunctions.route()
        .route(
            org.springframework.web.reactive.function.server.RequestPredicates.path("/stub/echo"),
            request -> {
              Map<String, String> seen = new HashMap<>();
              seen.put("authorization", request.headers().firstHeader("Authorization"));
              seen.put("correlationId", request.headers().firstHeader("X-Correlation-Id"));
              seen.put("idempotencyKey", request.headers().firstHeader("Idempotency-Key"));
              seen.values().removeIf(java.util.Objects::isNull);
              return ServerResponse.ok().contentType(MediaType.APPLICATION_JSON).bodyValue(seen);
            })
        .build();
  }

  /** The stub endpoints sit outside /api/v1 and must not need a token. */
  @Bean
  @Order(Ordered.HIGHEST_PRECEDENCE)
  SecurityWebFilterChain stubChain(ServerHttpSecurity http) {
    return http.securityMatcher(ServerWebExchangeMatchers.pathMatchers("/stub/**"))
        .authorizeExchange(exchanges -> exchanges.anyExchange().permitAll())
        .csrf(ServerHttpSecurity.CsrfSpec::disable)
        .build();
  }

  /** Allows {@link #ALLOWED_PER_KEY} calls per key, then denies, like an exhausted token bucket. */
  @Bean
  @Primary
  RateLimiter<Object> inMemoryRateLimiter() {
    Map<String, AtomicInteger> calls = new ConcurrentHashMap<>();
    return new RateLimiter<>() {
      @Override
      public Mono<RateLimiter.Response> isAllowed(String routeId, String id) {
        int n = calls.computeIfAbsent(id, k -> new AtomicInteger()).incrementAndGet();
        return Mono.just(
            new RateLimiter.Response(
                n <= ALLOWED_PER_KEY, Map.of("X-RateLimit-Remaining", remaining(n))));
      }

      @Override
      public Map<String, Object> getConfig() {
        return Map.of();
      }

      @Override
      public Class<Object> getConfigClass() {
        return Object.class;
      }

      @Override
      public Object newConfig() {
        return new Object();
      }

      private String remaining(int n) {
        return String.valueOf(Math.max(0, ALLOWED_PER_KEY - n));
      }
    };
  }
}
