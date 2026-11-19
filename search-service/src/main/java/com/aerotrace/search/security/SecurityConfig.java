package com.aerotrace.search.security;

import com.aerotrace.common.error.ApiErrorWriter;
import com.aerotrace.common.error.ErrorCode;
import com.aerotrace.common.security.AeroTraceJwtValidators;
import com.aerotrace.common.security.JwtClaims;
import com.aerotrace.common.security.PemKeyUtils;
import com.aerotrace.common.security.Role;
import com.aerotrace.search.config.SearchProperties;
import com.aerotrace.search.constant.ApiPaths;
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
 * Servlet JWT resource-server security, the same posture as the other services: stateless, 401 for
 * a missing or invalid token, 403 for a valid token without the role, and everything not listed is
 * denied.
 *
 * <p>Search and reports are for staff only (ADR 0004, decision 8): no passenger data is indexed, so
 * there is nothing a passenger could be limited to, and {@code PASSENGER} is refused on every
 * endpoint. The service enforces this itself; the gateway only checks that a token is present.
 */
@Configuration(proxyBeanMethods = false)
class SecurityConfig {

  private static final String AUTHENTICATION_REQUIRED = "Authentication is required";
  private static final String INSUFFICIENT_PERMISSIONS = "You do not have permission to do this";

  private static final String[] STAFF = {
    Role.AGENT.name(), Role.DISPATCHER.name(), Role.ADMIN.name()
  };

  @Bean
  JwtDecoder jwtDecoder(SearchProperties properties) {
    SearchProperties.Jwt jwt = properties.jwt();
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
      @Value("${aerotrace.search.docs.enabled:true}") boolean docsEnabled)
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
                  // Staff search and read reports.
                  .requestMatchers(HttpMethod.GET, ApiPaths.SEARCH_BAGS)
                  .hasAnyRole(STAFF)
                  .requestMatchers(HttpMethod.GET, ApiPaths.REPORTS_ALL)
                  .hasAnyRole(STAFF)
                  // The indexing lag is for the people who run operations.
                  .requestMatchers(HttpMethod.GET, ApiPaths.INDEX_STATUS)
                  .hasAnyRole(Role.DISPATCHER.name(), Role.ADMIN.name())
                  // Only an admin rebuilds the indexes or reads a rebuild job.
                  .requestMatchers(HttpMethod.POST, ApiPaths.REINDEX)
                  .hasRole(Role.ADMIN.name())
                  .requestMatchers(HttpMethod.GET, ApiPaths.REINDEX_JOB_ALL)
                  .hasRole(Role.ADMIN.name());
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
