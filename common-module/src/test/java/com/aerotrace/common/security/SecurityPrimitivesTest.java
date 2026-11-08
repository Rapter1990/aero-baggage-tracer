package com.aerotrace.common.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;

class SecurityPrimitivesTest {

  private static final String ISSUER = "https://identity.aerotrace.local";
  private static final String AUDIENCE = "aerotrace-api";

  private final OAuth2TokenValidator<Jwt> validator =
      AeroTraceJwtValidators.create(ISSUER, AUDIENCE);

  @AfterEach
  void clear() {
    SecurityContextHolder.clearContext();
  }

  private static Jwt jwt(String issuer, List<String> aud, Instant exp, Map<String, Object> extra) {
    Jwt.Builder b =
        Jwt.withTokenValue("t")
            .header("alg", "RS256")
            .subject("user-1")
            .issuedAt(exp.minusSeconds(600))
            .expiresAt(exp)
            .claim("aud", aud);
    if (issuer != null) {
      b.issuer(issuer);
    }
    extra.forEach(b::claim);
    return b.build();
  }

  @Test
  void acceptsValidToken() {
    var token = jwt(ISSUER, List.of(AUDIENCE), Instant.now().plusSeconds(300), Map.of());
    assertThat(validator.validate(token).hasErrors()).isFalse();
  }

  @Test
  void rejectsExpiredToken() {
    var token = jwt(ISSUER, List.of(AUDIENCE), Instant.now().minusSeconds(3600), Map.of());
    assertThat(validator.validate(token).hasErrors()).isTrue();
  }

  @Test
  void rejectsWrongIssuer() {
    var token = jwt("https://evil", List.of(AUDIENCE), Instant.now().plusSeconds(300), Map.of());
    assertThat(validator.validate(token).hasErrors()).isTrue();
  }

  @Test
  void rejectsWrongOrMissingAudience() {
    var wrong = jwt(ISSUER, List.of("other"), Instant.now().plusSeconds(300), Map.of());
    assertThat(validator.validate(wrong).hasErrors()).isTrue();
    var none = jwt(ISSUER, List.of(), Instant.now().plusSeconds(300), Map.of());
    assertThat(validator.validate(none).hasErrors()).isTrue();
  }

  @Test
  void rolesEnumExposesAuthorities() {
    assertThat(Role.values())
        .containsExactly(Role.PASSENGER, Role.AGENT, Role.DISPATCHER, Role.ADMIN);
    assertThat(Role.AGENT.authority()).isEqualTo("ROLE_AGENT");
    assertThat(Role.fromClaim("dispatcher")).contains(Role.DISPATCHER);
    assertThat(Role.fromClaim("ROLE_ADMIN")).contains(Role.ADMIN);
    assertThat(Role.fromClaim("root")).isEmpty();
  }

  @Test
  void helperReadsSubjectRolesAndPassengerRef() {
    var token =
        jwt(
            ISSUER,
            List.of(AUDIENCE),
            Instant.now().plusSeconds(300),
            Map.of(JwtClaims.ROLES, List.of("AGENT", "unknown"), JwtClaims.PASSENGER_REF, "P-901"));
    SecurityContextHolder.getContext()
        .setAuthentication(new TestingAuthenticationToken(token, "n"));

    assertThat(SecurityContextHelper.currentSubject()).contains("user-1");
    assertThat(SecurityContextHelper.currentRoles()).containsExactly(Role.AGENT);
    assertThat(SecurityContextHelper.hasRole(Role.AGENT)).isTrue();
    assertThat(SecurityContextHelper.hasRole(Role.ADMIN)).isFalse();
    assertThat(SecurityContextHelper.currentPassengerRef()).contains("P-901");
  }

  @Test
  void helperIsEmptyWithoutJwtAuthentication() {
    assertThat(SecurityContextHelper.currentSubject()).isEmpty();
    assertThat(SecurityContextHelper.currentRoles()).isEmpty();
    SecurityContextHolder.getContext()
        .setAuthentication(new TestingAuthenticationToken("u", "p", "ROLE_ADMIN"));
    assertThat(SecurityContextHelper.currentRoles()).isEmpty();
    assertThat(SecurityContextHelper.currentPassengerRef()).isEmpty();
  }
}
