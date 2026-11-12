package com.aerotrace.gateway.support;

import static org.springframework.cloud.gateway.server.mvc.filter.BeforeFilterFunctions.uri;
import static org.springframework.cloud.gateway.server.mvc.handler.GatewayRouterFunctions.route;
import static org.springframework.cloud.gateway.server.mvc.handler.HandlerFunctions.http;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.web.servlet.function.RequestPredicates;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerResponse;

/** Routes that proxy to {@link TestDownstream} over real HTTP instead of a real service. */
@TestConfiguration(proxyBeanMethods = false)
public class TestRoutesConfig {

  @Bean
  RouterFunction<ServerResponse> testEchoRoute() {
    return route("test-echo")
        .route(RequestPredicates.path("/api/v1/echo/**"), http())
        .before(uri(TestDownstream.uri()))
        .build();
  }

  /** Answers after 3 seconds, to test the gateway's downstream timeouts. */
  @Bean
  RouterFunction<ServerResponse> testSlowRoute() {
    return route("test-slow")
        .route(RequestPredicates.path("/api/v1/slow/**"), http())
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
}
