package com.aerotrace.gateway.config;

import com.aerotrace.common.error.ApiErrorWriter;
import com.aerotrace.common.error.ErrorCode;
import com.aerotrace.common.security.AeroTraceJwtValidators;
import com.aerotrace.common.security.JwtClaims;
import com.aerotrace.common.security.Role;
import com.aerotrace.gateway.ratelimit.RateLimitFilter;
import com.aerotrace.gateway.ratelimit.RateLimiter;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.web.client.RestTemplate;

/**
 * Servlet JWT resource-server security at the edge. The signature is checked with the public key
 * identity-service publishes (JWKS), and exp, iss and aud are verified here; 401 for
 * missing/invalid tokens, 403 for a valid token without the required role.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(GatewayProperties.class)
class SecurityConfig {

  @Bean
  JwtDecoder jwtDecoder(GatewayProperties properties) {
    SimpleClientHttpRequestFactory http = new SimpleClientHttpRequestFactory();
    http.setConnectTimeout(Duration.ofSeconds(2));
    http.setReadTimeout(Duration.ofSeconds(2));

    NimbusJwtDecoder decoder =
        NimbusJwtDecoder.withJwkSetUri(properties.jwkSetUri())
            .jwsAlgorithms(
                algorithms -> {
                  algorithms.add(SignatureAlgorithm.RS256);
                  algorithms.add(SignatureAlgorithm.ES256);
                })
            .restOperations(new RestTemplate(http))
            .build();
    decoder.setJwtValidator(
        AeroTraceJwtValidators.create(properties.issuer(), properties.audience()));
    return decoder;
  }

  @Bean
  SecurityFilterChain securityFilterChain(
      HttpSecurity http,
      JwtDecoder jwtDecoder,
      GatewayProperties properties,
      ApiErrorWriter errors,
      RateLimiter rateLimiter)
      throws Exception {
    AuthenticationEntryPoint unauthorized =
        (request, response, ex) -> {
          response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
          errors.write(request, response, ErrorCode.UNAUTHENTICATED, "Authentication required");
        };
    AccessDeniedHandler forbidden =
        (request, response, ex) ->
            errors.write(request, response, ErrorCode.ACCESS_DENIED, "Insufficient permissions");

    http.csrf(AbstractHttpConfigurer::disable)
        .httpBasic(AbstractHttpConfigurer::disable)
        .formLogin(AbstractHttpConfigurer::disable)
        .logout(AbstractHttpConfigurer::disable)
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(
            requests -> {
              requests.requestMatchers("/actuator/health/**", "/actuator/info").permitAll();
              if (properties.docs().enabled()) {
                // API documentation only; the endpoints it describes still need a token.
                requests
                    .requestMatchers(
                        "/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs/**", "/service-docs/**")
                    .permitAll();
              }
              requests.requestMatchers("/actuator/**").hasRole(Role.ADMIN.name());
              // Obtaining a token is the one unauthenticated API call.
              requests.requestMatchers(HttpMethod.POST, "/api/v1/auth/token").permitAll();
              for (GatewayProperties.RoleRule rule : properties.roleRules()) {
                requests
                    .requestMatchers(rule.pattern())
                    .hasAnyRole(rule.roles().stream().map(Role::name).toArray(String[]::new));
              }
              requests.requestMatchers("/api/v1/**").authenticated();
              requests.anyRequest().denyAll();
            })
        .exceptionHandling(
            handling ->
                handling.authenticationEntryPoint(unauthorized).accessDeniedHandler(forbidden))
        .oauth2ResourceServer(
            oauth ->
                oauth
                    .authenticationEntryPoint(unauthorized)
                    .accessDeniedHandler(forbidden)
                    .jwt(
                        jwt ->
                            jwt.decoder(jwtDecoder).jwtAuthenticationConverter(rolesConverter())))
        .addFilterAfter(
            new RateLimitFilter(rateLimiter, errors), BearerTokenAuthenticationFilter.class);
    return http.build();
  }

  /** Maps the {@code roles} claim to ROLE_ authorities; unknown role names grant nothing. */
  private static JwtAuthenticationConverter rolesConverter() {
    JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
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
            return List.<GrantedAuthority>of();
          }
          return claim.stream()
              .flatMap(value -> Role.fromClaim(value).stream())
              .<GrantedAuthority>map(role -> new SimpleGrantedAuthority(role.authority()))
              .toList();
        });
    return converter;
  }
}
