package com.aerotrace.gateway.filter;

import com.aerotrace.common.error.ErrorCode;
import com.aerotrace.gateway.problem.GatewayProblems;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.security.Principal;
import java.util.Optional;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.filter.ratelimit.RateLimiter;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Applies the rate limiter to every routed request and answers 429 as a ProblemDetail (the stock
 * RequestRateLimiter filter returns an empty body). Callers are keyed by token subject, falling
 * back to the remote address.
 */
@Component
public class RateLimitGlobalFilter implements GlobalFilter, Ordered {

  private final RateLimiter<?> rateLimiter;
  private final GatewayProblems problems;

  public RateLimitGlobalFilter(RateLimiter<?> rateLimiter, GatewayProblems problems) {
    this.rateLimiter = rateLimiter;
    this.problems = problems;
  }

  @Override
  public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
    Route route = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
    String routeId = route == null ? "unrouted" : route.getId();
    return callerKey(exchange)
        .flatMap(
            key ->
                rateLimiter
                    .isAllowed(routeId, key)
                    .flatMap(
                        response -> {
                          response.getHeaders().forEach(exchange.getResponse().getHeaders()::set);
                          if (response.isAllowed()) {
                            return chain.filter(exchange);
                          }
                          exchange.getResponse().getHeaders().set("Retry-After", "1");
                          return problems.write(
                              exchange, ErrorCode.RATE_LIMITED, "Too many requests, retry later");
                        }));
  }

  private static Mono<String> callerKey(ServerWebExchange exchange) {
    return exchange
        .getPrincipal()
        .mapNotNull(Principal::getName)
        .switchIfEmpty(
            Mono.fromSupplier(
                () ->
                    Optional.ofNullable(exchange.getRequest().getRemoteAddress())
                        .map(InetSocketAddress::getAddress)
                        .map(InetAddress::getHostAddress)
                        .orElse("unknown")));
  }

  @Override
  public int getOrder() {
    return -10;
  }
}
