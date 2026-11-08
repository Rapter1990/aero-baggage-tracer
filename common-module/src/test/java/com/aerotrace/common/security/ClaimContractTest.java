package com.aerotrace.common.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Pins the claim names identity-service issues (backlog #8: iss, aud, sub, roles, passengerRef).
 */
class ClaimContractTest {

  @Test
  void claimNamesMatchIdentityContract() {
    assertThat(JwtClaims.PASSENGER_REF).isEqualTo("passengerRef");
    assertThat(JwtClaims.ROLES).isEqualTo("roles");
  }

  @Test
  void readsPassengerRefFromTokenIssuedWithTheContractClaimName() {
    Jwt jwt =
        Jwt.withTokenValue("t")
            .header("alg", "RS256")
            .subject("u")
            .claim("passengerRef", "P-901")
            .build();
    SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken(jwt, "n"));
    try {
      assertThat(SecurityContextHelper.requirePassengerRef()).isEqualTo("P-901");
    } finally {
      SecurityContextHolder.clearContext();
    }
  }
}
