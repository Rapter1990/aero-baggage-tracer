package com.aerotrace.gateway.config;

import com.aerotrace.common.error.ErrorCode;
import com.aerotrace.common.security.AeroTraceJwtValidators;
import com.aerotrace.common.security.JwtClaims;
import com.aerotrace.common.security.Role;
import com.aerotrace.gateway.problem.GatewayProblems;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.ReactiveJwtAuthenticationConverter;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.ServerAuthenticationEntryPoint;
import org.springframework.security.web.server.authorization.ServerAccessDeniedHandler;
import reactor.core.publisher.Flux;

/**
 * JWT resource-server security at the edge: signature (JWKS), exp, iss and aud are verified here;
 * 401 for missing/invalid tokens, 403 for a valid token without the required role.
 */
@Configuration(proxyBeanMethods = false)
@EnableWebFluxSecurity
class SecurityConfig {

  @Bean
  ReactiveJwtDecoder jwtDecoder(GatewayProperties properties) {
    NimbusReactiveJwtDecoder decoder =
        NimbusReactiveJwtDecoder.withJwkSetUri(properties.jwkSetUri())
            .jwsAlgorithms(
                algorithms -> {
                  algorithms.add(SignatureAlgorithm.RS256);
                  algorithms.add(SignatureAlgorithm.ES256);
                })
            .build();
    decoder.setJwtValidator(
        AeroTraceJwtValidators.create(properties.issuer(), properties.audience()));
    return decoder;
  }

  @Bean
  SecurityWebFilterChain securityWebFilterChain(
      ServerHttpSecurity http, GatewayProperties properties, GatewayProblems problems) {
    ServerAuthenticationEntryPoint unauthorized =
        (exchange, ex) -> {
          exchange.getResponse().getHeaders().set(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
          return problems.write(exchange, ErrorCode.UNAUTHENTICATED, "Authentication required");
        };
    ServerAccessDeniedHandler forbidden =
        (exchange, ex) ->
            problems.write(exchange, ErrorCode.ACCESS_DENIED, "Insufficient permissions");

    return http.csrf(ServerHttpSecurity.CsrfSpec::disable)
        .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
        .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
        .authorizeExchange(
            exchanges -> {
              exchanges.pathMatchers("/actuator/health/**", "/actuator/info").permitAll();
              exchanges.pathMatchers("/actuator/**").hasRole(Role.ADMIN.name());
              // Obtaining a token is the one unauthenticated API call.
              exchanges.pathMatchers(HttpMethod.POST, "/api/v1/auth/token").permitAll();
              for (GatewayProperties.RoleRule rule : properties.roleRules()) {
                exchanges
                    .pathMatchers(rule.pattern())
                    .hasAnyRole(rule.roles().stream().map(Role::name).toArray(String[]::new));
              }
              exchanges.pathMatchers("/api/v1/**").authenticated();
              exchanges.anyExchange().denyAll();
            })
        .exceptionHandling(
            handling ->
                handling.authenticationEntryPoint(unauthorized).accessDeniedHandler(forbidden))
        .oauth2ResourceServer(
            oauth ->
                oauth
                    .authenticationEntryPoint(unauthorized)
                    .accessDeniedHandler(forbidden)
                    .jwt(jwt -> jwt.jwtAuthenticationConverter(rolesConverter())))
        .build();
  }

  /** Maps the {@code roles} claim to ROLE_ authorities; unknown role names grant nothing. */
  private static ReactiveJwtAuthenticationConverter rolesConverter() {
    ReactiveJwtAuthenticationConverter converter = new ReactiveJwtAuthenticationConverter();
    converter.setJwtGrantedAuthoritiesConverter(
        jwt -> {
          List<String> claim;
          try {
            claim = jwt.getClaimAsStringList(JwtClaims.ROLES);
          } catch (RuntimeException malformedClaim) {
            // A roles claim of an unexpected type grants nothing rather than failing the request.
            claim = null;
          }
          if (claim == null) {
            return Flux.empty();
          }
          return Flux.fromIterable(claim)
              .flatMapIterable(value -> Role.fromClaim(value).stream().toList())
              .map(role -> (GrantedAuthority) new SimpleGrantedAuthority(role.authority()));
        });
    return converter;
  }
}
