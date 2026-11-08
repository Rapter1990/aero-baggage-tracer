package com.aerotrace.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aerotrace.common.error.ApiException;
import com.aerotrace.common.error.ErrorCode;
import com.aerotrace.common.logging.LogMasker;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;

class HardeningTest {

  @AfterEach
  void clear() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void rejectsTokenWithoutExpiry() {
    Jwt noExp =
        Jwt.withTokenValue("t")
            .header("alg", "RS256")
            .issuer("https://id")
            .claim("aud", java.util.List.of("api"))
            .issuedAt(Instant.now())
            .build();
    assertThat(AeroTraceJwtValidators.create("https://id", "api").validate(noExp).hasErrors())
        .isTrue();
  }

  @Test
  void failsFastOnBlankConfiguration() {
    assertThatThrownBy(() -> AeroTraceJwtValidators.create("https://id", " "))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> AeroTraceJwtValidators.create("", "api"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static void authenticateWithRef(Object ref) {
    Jwt jwt =
        Jwt.withTokenValue("t")
            .header("alg", "RS256")
            .subject("u")
            .claim(JwtClaims.PASSENGER_REF, ref)
            .build();
    SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken(jwt, "n"));
  }

  @Test
  void passengerRefFailsClosedOnBlankOrNonString() {
    authenticateWithRef("  ");
    assertThat(SecurityContextHelper.currentPassengerRef()).isEmpty();
    authenticateWithRef(java.util.List.of("P-1", "P-2"));
    assertThat(SecurityContextHelper.currentPassengerRef()).isEmpty();
    assertThatThrownBy(SecurityContextHelper::requirePassengerRef)
        .isInstanceOfSatisfying(
            ApiException.class, e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.ACCESS_DENIED));
  }

  @Test
  void requirePassengerRefReturnsValidRef() {
    authenticateWithRef("P-901");
    assertThat(SecurityContextHelper.requirePassengerRef()).isEqualTo("P-901");
  }

  @Test
  void masksBasicAuthSecretsAndSuffixedPassengerRefs() {
    String masked =
        LogMasker.mask(
            "Authorization: Basic dXNlcjpwYXNz refresh_token=abc123 {\"password\":\"hunter2\"} p-901A ok");
    assertThat(masked).doesNotContain("dXNlcjpwYXNz", "abc123", "hunter2", "901A").contains("ok");
  }

  @Test
  void truncatesVeryLongInput() {
    String masked = LogMasker.mask("x".repeat(100_000));
    assertThat(masked.length()).isLessThan(20_000);
  }
}
