package com.aerotrace.notification.security;

import com.aerotrace.common.error.ApiErrorWriter;
import com.aerotrace.common.error.ErrorCode;
import com.aerotrace.common.security.AeroTraceJwtValidators;
import com.aerotrace.common.security.JwtClaims;
import com.aerotrace.common.security.PemKeyUtils;
import com.aerotrace.common.security.Role;
import com.aerotrace.notification.config.NotificationProperties;
import com.aerotrace.notification.constant.ApiPaths;
import com.aerotrace.notification.constant.ErrorMessages;
import java.security.interfaces.RSAPublicKey;
import java.util.Arrays;
import java.util.List;
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
 * Servlet JWT resource-server security, the same posture as the other services: stateless, 401 for
 * a missing or invalid token, 403 for a valid token without a known role, and everything not listed
 * is denied. All four roles read their own notifications; nothing can be written (ADR 0005,
 * decision 13). Ownership is checked in the query, not here: another user's id is a 404.
 */
@Configuration(proxyBeanMethods = false)
class SecurityConfig {

  @Bean
  JwtDecoder jwtDecoder(NotificationProperties properties) {
    NotificationProperties.Jwt jwt = properties.jwt();
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
      NotificationProperties properties)
      throws Exception {
    AuthenticationEntryPoint unauthorized =
        (request, response, ex) -> {
          response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
          errors.write(
              request, response, ErrorCode.UNAUTHENTICATED, ErrorMessages.AUTHENTICATION_REQUIRED);
        };
    AccessDeniedHandler forbidden =
        (request, response, ex) ->
            errors.write(
                request, response, ErrorCode.ACCESS_DENIED, ErrorMessages.INSUFFICIENT_PERMISSIONS);
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
                  // Every role reads its own notifications, and only reads them.
                  .requestMatchers(
                      HttpMethod.GET, ApiPaths.NOTIFICATIONS, ApiPaths.NOTIFICATION_ANY)
                  .hasAnyRole(everyRole);
              if (properties.docs().enabled()) {
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
