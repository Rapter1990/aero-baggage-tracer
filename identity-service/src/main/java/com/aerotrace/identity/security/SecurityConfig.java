package com.aerotrace.identity.security;

import com.aerotrace.common.error.ErrorCode;
import com.aerotrace.common.security.AeroTraceJwtValidators;
import com.aerotrace.common.security.JwtClaims;
import com.aerotrace.common.security.Role;
import com.aerotrace.identity.keys.SigningKeyService;
import com.aerotrace.identity.policy.Permission;
import com.aerotrace.identity.token.TokenProperties;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.JWSKeySelector;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;

/**
 * Servlet JWT resource-server security. Same posture as the gateway: stateless, 401 for a missing
 * or invalid token, 403 for a valid token without the role, and everything not listed is denied.
 * The service verifies tokens with its own published keys in process (no HTTP call to itself).
 */
@Configuration(proxyBeanMethods = false)
class SecurityConfig {

  private static final Set<JWSAlgorithm> ALGORITHMS =
      Set.of(JWSAlgorithm.RS256, JWSAlgorithm.ES256);

  /** Reachable without a token. A token sent to one of these is ignored, never held against you. */
  private static boolean isPublic(HttpServletRequest request) {
    String path = request.getRequestURI();
    return path.equals("/api/v1/auth/token")
        || path.equals("/.well-known/jwks.json")
        || path.equals("/actuator/info")
        || path.equals("/actuator/health")
        || path.startsWith("/actuator/health/");
  }

  @Bean
  JwtDecoder jwtDecoder(SigningKeyService keys, TokenProperties tokens) {
    JWKSource<SecurityContext> source =
        (selector, context) -> selector.select(keys.publishedKeys());
    JWSKeySelector<SecurityContext> selector =
        (header, context) ->
            ALGORITHMS.contains(header.getAlgorithm())
                ? new JWSVerificationKeySelector<>(header.getAlgorithm(), source)
                    .selectJWSKeys(header, context)
                : List.of();
    DefaultJWTProcessor<SecurityContext> processor = new DefaultJWTProcessor<>();
    processor.setJWSKeySelector(selector);
    // Claims are checked by the validator below, as in the gateway.
    processor.setJWTClaimsSetVerifier((claims, context) -> {});
    NimbusJwtDecoder decoder = new NimbusJwtDecoder(processor);
    decoder.setJwtValidator(AeroTraceJwtValidators.create(tokens.issuer(), tokens.audience()));
    return decoder;
  }

  @Bean
  SecurityFilterChain securityFilterChain(
      HttpSecurity http, JwtDecoder jwtDecoder, ProblemWriter problems) throws Exception {
    AuthenticationEntryPoint unauthorized =
        (request, response, ex) -> {
          response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
          problems.write(response, ErrorCode.UNAUTHENTICATED, "Authentication required");
        };
    AccessDeniedHandler forbidden =
        (request, response, ex) ->
            problems.write(response, ErrorCode.ACCESS_DENIED, "Insufficient permissions");

    DefaultBearerTokenResolver bearer = new DefaultBearerTokenResolver();
    BearerTokenResolver resolver = request -> isPublic(request) ? null : bearer.resolve(request);

    String[] everyRole = Arrays.stream(Role.values()).map(Role::name).toArray(String[]::new);
    String[] rotators =
        Permission.SIGNING_KEY_ROTATE.roles().stream().map(Role::name).toArray(String[]::new);

    http.csrf(AbstractHttpConfigurer::disable)
        .httpBasic(AbstractHttpConfigurer::disable)
        .formLogin(AbstractHttpConfigurer::disable)
        .logout(AbstractHttpConfigurer::disable)
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(
            requests ->
                requests
                    .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info")
                    .permitAll()
                    .requestMatchers(HttpMethod.GET, "/.well-known/jwks.json")
                    .permitAll()
                    // Any method: the controller answers a wrong one with 405, not a login prompt.
                    .requestMatchers("/api/v1/auth/token")
                    .permitAll()
                    .requestMatchers(HttpMethod.POST, "/api/v1/auth/keys/rotate")
                    .hasAnyRole(rotators)
                    .requestMatchers(HttpMethod.GET, "/api/v1/auth/me", "/api/v1/auth/policy")
                    .hasAnyRole(everyRole)
                    // Anything new must be given an explicit rule above.
                    .anyRequest()
                    .denyAll())
        .exceptionHandling(
            handling ->
                handling.authenticationEntryPoint(unauthorized).accessDeniedHandler(forbidden))
        .oauth2ResourceServer(
            oauth ->
                oauth
                    .bearerTokenResolver(resolver)
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
