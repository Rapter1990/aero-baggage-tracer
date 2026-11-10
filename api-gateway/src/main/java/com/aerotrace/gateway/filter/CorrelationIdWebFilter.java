package com.aerotrace.gateway.filter;

import com.aerotrace.common.correlation.CorrelationId;
import com.aerotrace.gateway.problem.GatewayProblems;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Runs before security so even 401/403/429 responses carry the id. Keeps a well-formed inbound
 * {@code X-Correlation-Id}, replaces anything else, and propagates the id downstream.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdWebFilter implements WebFilter {

  @Override
  public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
    String inbound = exchange.getRequest().getHeaders().getFirst(CorrelationId.HEADER);
    String id = CorrelationId.isValid(inbound) ? inbound : CorrelationId.generate();

    ServerHttpRequest request =
        exchange
            .getRequest()
            .mutate()
            .headers(headers -> headers.set(CorrelationId.HEADER, id))
            .build();
    exchange.getAttributes().put(GatewayProblems.CORRELATION_ATTRIBUTE, id);
    // Set at commit time so the downstream service's echo of the header is replaced, not doubled.
    exchange
        .getResponse()
        .beforeCommit(
            () -> {
              exchange.getResponse().getHeaders().set(CorrelationId.HEADER, id);
              return Mono.empty();
            });
    return chain.filter(exchange.mutate().request(request).build());
  }
}
