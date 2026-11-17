package com.aerotrace.recovery.security;

import com.aerotrace.common.error.ApiErrorWriter;
import com.aerotrace.common.error.ErrorCode;
import com.aerotrace.common.security.AeroTraceJwtValidators;
import com.aerotrace.common.security.JwtClaims;
import com.aerotrace.common.security.PemKeyUtils;
import com.aerotrace.common.security.Role;
import com.aerotrace.recovery.config.RecoveryProperties;
import com.aerotrace.recovery.constant.ApiPaths;
import java.security.interfaces.RSAPublicKey;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;

/**
 * Servlet JWT resource-server security, the same posture as the journey-service: stateless, 401 for
 * a missing or invalid token, 403 for a valid token without the role, and everything not listed is
 * denied. Only agents and dispatchers may move a case (spec section 11).
 */
@Configuration(proxyBeanMethods = false)
class SecurityConfig {

  private static final String AUTHENTICATION_REQUIRED = "Authentication is required";
  private static final String INSUFFICIENT_PERMISSIONS = "You do not have permission to do this";

  @Bean
  JwtDecoder jwtDecoder(RecoveryProperties properties) {
    RecoveryProperties.Jwt jwt = properties.jwt();
    RSAPublicKey key =
        (RSAPublicKey) PemKeyUtils.readPublicKey(jwt.publicKey(), jwt.publicKeyLocation());
    NimbusJwtDecoder decoder =
        NimbusJwtDecoder.withPublicKey(key).signatureAlgorithm(SignatureAlgorithm.RS256).build();
    decoder.setJwtValidator(AeroTraceJwtValidators.create(JwtClaims.ISSUER, jwt.audience()));
    return decoder;
  }

  @Bean
  SecurityFilterChain securityFilterChain(
      HttpSecurity http,
      JwtDecoder jwtDecoder,
      ApiErrorWriter errors,
      @Value("${aerotrace.recovery.docs.enabled:true}") boolean docsEnabled)
      throws Exception {
    AuthenticationEntryPoint unauthorized =
        (request, response, ex) -> {
          response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
          errors.write(request, response, ErrorCode.UNAUTHENTICATED, AUTHENTICATION_REQUIRED);
        };
    AccessDeniedHandler forbidden =
        (request, response, ex) ->
            errors.write(request, response, ErrorCode.ACCESS_DENIED, INSUFFICIENT_PERMISSIONS);

    http.csrf(AbstractHttpConfigurer::disable)
        .httpBasic(AbstractHttpConfigurer::disable)
        .formLogin(AbstractHttpConfigurer::disable)
        .logout(AbstractHttpConfigurer::disable)
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(
            requests -> {
              requests
                  .requestMatchers(ApiPaths.HEALTH, ApiPaths.HEALTH_ALL, ApiPaths.INFO)
                  .permitAll()
                  // Staff open and list cases.
                  .requestMatchers(HttpMethod.POST, ApiPaths.CASES)
                  .hasAnyRole(Role.AGENT.name(), Role.DISPATCHER.name(), Role.ADMIN.name())
                  .requestMatchers(HttpMethod.GET, ApiPaths.CASES)
                  .hasAnyRole(Role.AGENT.name(), Role.DISPATCHER.name(), Role.ADMIN.name())
                  // Staff read the policy; only admins change it (spec section 11).
                  .requestMatchers(HttpMethod.GET, ApiPaths.POLICY)
                  .hasAnyRole(Role.AGENT.name(), Role.DISPATCHER.name(), Role.ADMIN.name())
                  .requestMatchers(HttpMethod.PUT, ApiPaths.POLICY)
                  .hasRole(Role.ADMIN.name())
                  // Only agents and dispatchers move a case.
                  .requestMatchers(HttpMethod.PATCH, ApiPaths.TRANSITIONS_ALL)
                  .hasAnyRole(Role.AGENT.name(), Role.DISPATCHER.name())
                  // The same two roles hand a case to a handler.
                  .requestMatchers(HttpMethod.PUT, ApiPaths.ASSIGNMENT_ALL)
                  .hasAnyRole(Role.AGENT.name(), Role.DISPATCHER.name());
              if (docsEnabled) {
                requests
                    .requestMatchers(
                        HttpMethod.GET,
                        ApiPaths.API_DOCS,
                        ApiPaths.API_DOCS_ALL,
                        ApiPaths.API_DOCS_YAML)
                    .permitAll();
              }
              // Anything new must be given an explicit rule above.
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
                            jwt.decoder(jwtDecoder).jwtAuthenticationConverter(rolesConverter())));
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
