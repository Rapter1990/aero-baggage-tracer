package com.aerotrace.identity.security;

import com.aerotrace.common.error.ApiErrorWriter;
import com.aerotrace.common.error.ErrorCode;
import com.aerotrace.common.security.JwtClaims;
import com.aerotrace.common.security.Role;
import com.aerotrace.identity.constant.ApiMessages;
import com.aerotrace.identity.constant.ApiPaths;
import com.aerotrace.identity.constant.JwtConstants;
import com.aerotrace.identity.constant.SecurityConstants;
import com.aerotrace.identity.security.jwt.JwtTokenService;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Arrays;
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
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;

/**
 * Servlet JWT resource-server security. Same posture as the gateway: stateless, 401 for a missing
 * or invalid token, 403 for a valid token without the role, and everything not listed is denied.
 * The service verifies tokens itself with {@link JwtTokenService}, with the same key pair it signs
 * with (no HTTP call to itself).
 */
@Configuration(proxyBeanMethods = false)
class SecurityConfig {

  /** Reachable without a token. A token sent to one of these is ignored, never held against you. */
  private static boolean isPublic(HttpServletRequest request) {
    String path = request.getRequestURI();
    return path.equals(ApiPaths.TOKEN)
        || path.equals(ApiPaths.JWKS)
        || path.startsWith(ApiPaths.API_DOCS)
        || path.equals(ApiPaths.INFO)
        || path.equals(ApiPaths.HEALTH)
        || path.startsWith(ApiPaths.HEALTH + "/");
  }

  /**
   * Verifies a bearer token with {@link JwtTokenService#parseClaims} (signature, issuer, audience
   * and expiry) and hands Spring Security the claims it needs.
   */
  @Bean
  JwtDecoder jwtDecoder(JwtTokenService tokens) {
    return token -> {
      try {
        return toJwt(token, tokens.parseClaims(token));
      } catch (JwtException | IllegalArgumentException e) {
        // The reason (expired? wrong audience? bad signature?) is not passed on to the caller.
        throw new BadJwtException("The token is not valid", e);
      }
    };
  }

  private static Jwt toJwt(String token, Claims claims) {
    Jwt.Builder jwt =
        Jwt.withTokenValue(token)
            .header("alg", JwtConstants.SIGNATURE_ALGORITHM)
            .header("typ", JwtConstants.HEADER_TYPE)
            .subject(claims.getSubject())
            .issuer(claims.getIssuer())
            .audience(claims.getAudience())
            .jti(claims.getId())
            .claim(JwtClaims.ROLES, claims.get(JwtClaims.ROLES))
            .issuedAt(claims.getIssuedAt() == null ? null : claims.getIssuedAt().toInstant())
            .expiresAt(claims.getExpiration().toInstant());
    Object passengerRef = claims.get(JwtClaims.PASSENGER_REF);
    if (passengerRef != null) {
      jwt.claim(JwtClaims.PASSENGER_REF, passengerRef);
    }
    return jwt.build();
  }

  @Bean
  SecurityFilterChain securityFilterChain(
      HttpSecurity http,
      JwtDecoder jwtDecoder,
      ApiErrorWriter errors,
      @Value("${aerotrace.identity.docs.enabled:false}") boolean docsEnabled)
      throws Exception {
    AuthenticationEntryPoint unauthorized =
        (request, response, ex) -> {
          response.setHeader(
              HttpHeaders.WWW_AUTHENTICATE, SecurityConstants.WWW_AUTHENTICATE_BEARER);
          errors.write(
              request, response, ErrorCode.UNAUTHENTICATED, ApiMessages.AUTHENTICATION_REQUIRED);
        };
    AccessDeniedHandler forbidden =
        (request, response, ex) ->
            errors.write(
                request, response, ErrorCode.ACCESS_DENIED, ApiMessages.INSUFFICIENT_PERMISSIONS);

    DefaultBearerTokenResolver bearer = new DefaultBearerTokenResolver();
    BearerTokenResolver resolver = request -> isPublic(request) ? null : bearer.resolve(request);

    String[] everyRole = Arrays.stream(Role.values()).map(Role::name).toArray(String[]::new);

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
                  .requestMatchers(HttpMethod.GET, ApiPaths.JWKS)
                  .permitAll()
                  // Any method: the controller answers a wrong one with 405, not a login prompt.
                  .requestMatchers(ApiPaths.TOKEN)
                  .permitAll();
              if (docsEnabled) {
                // The definition only; the endpoints it describes still need their own rules.
                requests
                    .requestMatchers(
                        HttpMethod.GET,
                        ApiPaths.API_DOCS,
                        ApiPaths.API_DOCS_ALL,
                        ApiPaths.API_DOCS_YAML)
                    .permitAll();
              }
              requests
                  .requestMatchers(HttpMethod.GET, ApiPaths.ME, ApiPaths.POLICY)
                  .hasAnyRole(everyRole)
                  // Anything new must be given an explicit rule above.
                  .anyRequest()
                  .denyAll();
            })
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
