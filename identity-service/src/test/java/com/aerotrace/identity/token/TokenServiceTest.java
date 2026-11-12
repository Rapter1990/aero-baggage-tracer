package com.aerotrace.identity.token;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aerotrace.common.error.ApiException;
import com.aerotrace.common.error.ErrorCode;
import com.aerotrace.common.security.Role;
import com.aerotrace.identity.audit.AuthAuditLog;
import com.aerotrace.identity.audit.AuthEventType;
import com.aerotrace.identity.audit.AuthOutcome;
import com.aerotrace.identity.model.domain.AppUser;
import com.aerotrace.identity.model.dto.request.TokenRequest;
import com.aerotrace.identity.model.dto.response.TokenResponse;
import com.aerotrace.identity.security.jwt.JwtTokenService;
import com.aerotrace.identity.user.UserService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.password.PasswordEncoder;

/** The parts of sign-in that are hard to see from outside. */
class TokenServiceTest {

  private static final String DUMMY_HASH =
      "$2a$04$dummydummydummydummydummydummydummydummydummydummydummy";

  private final UserService users = mock(UserService.class);
  private final JwtTokenService jwt = mock(JwtTokenService.class);
  private final AuthAuditLog audit = mock(AuthAuditLog.class);
  private final PasswordEncoder encoder = mock(PasswordEncoder.class);
  private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
  private TokenService service;

  @BeforeEach
  void setUp() {
    when(encoder.encode(any())).thenReturn(DUMMY_HASH);
    when(jwt.ttl()).thenReturn(Duration.ofMinutes(15));
    service = new TokenService(users, jwt, audit, encoder, meters);
  }

  private static AppUser user(String name, String passengerRef, boolean enabled, Role... roles) {
    return new AppUser(
        UUID.randomUUID(), name, "$2a$04$hash", passengerRef, enabled, Set.of(roles));
  }

  @Test
  void aSuccessfulSignInSignsATokenForTheUserWithSortedRolesAndTheirPassengerReference() {
    AppUser pax = user("jane", "P-00000000000000000001", true, Role.PASSENGER, Role.AGENT);
    when(users.findByUsername("jane")).thenReturn(Optional.of(pax));
    when(users.passwordMatches(pax, "right-password-1")).thenReturn(true);
    when(jwt.generateToken(any(), any(), any())).thenReturn("signed.jwt.token");

    TokenResponse response = service.issue("jane", "right-password-1");

    verify(jwt)
        .generateToken(
            pax.id().toString(), List.of("AGENT", "PASSENGER"), "P-00000000000000000001");
    assertThat(response.accessToken()).isEqualTo("signed.jwt.token");
    assertThat(response.tokenType()).isEqualTo("Bearer");
    assertThat(response.expiresIn()).isEqualTo(900);
    verify(audit).record(AuthEventType.TOKEN_ISSUED, AuthOutcome.SUCCESS, "jane", pax.id());
    assertThat(meters.counter("identity.auth.token.requests", "outcome", "success").count())
        .isEqualTo(1);
  }

  @Test
  void anUnknownUserStillCostsAPasswordHashCheck() {
    when(users.findByUsername("ghost")).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.issue("ghost", "some-password-1"))
        .isInstanceOf(ApiException.class)
        .extracting(e -> ((ApiException) e).errorCode())
        .isEqualTo(ErrorCode.UNAUTHENTICATED);

    // Same work as for a real user, so response time does not reveal which usernames exist.
    verify(encoder).matches("some-password-1", DUMMY_HASH);
    verify(jwt, never()).generateToken(any(), any(), any());
    verify(audit).record(AuthEventType.LOGIN_FAILED, AuthOutcome.FAILURE, null, null);
    assertThat(meters.counter("identity.auth.token.requests", "outcome", "failure").count())
        .isEqualTo(1);
  }

  @Test
  void aDisabledAccountIsRefusedLikeAWrongPasswordAndAuditedAgainstIt() {
    AppUser disabled = user("old", null, false, Role.AGENT);
    when(users.findByUsername("old")).thenReturn(Optional.of(disabled));
    when(users.passwordMatches(eq(disabled), any())).thenReturn(true);

    assertThatThrownBy(() -> service.issue("old", "right-password-1"))
        .isInstanceOf(ApiException.class);

    verify(jwt, never()).generateToken(any(), any(), any());
    verify(audit).record(AuthEventType.LOGIN_FAILED, AuthOutcome.FAILURE, "old", disabled.id());
  }

  @Test
  void anOversizedPasswordNeverReachesTheHashFunctionAndIsAnOrdinaryFailure() {
    AppUser agent = user("agent", null, true, Role.AGENT);
    when(users.findByUsername("agent")).thenReturn(Optional.of(agent));
    String tooLong = "p".repeat(100);

    assertThatThrownBy(() -> service.issue("agent", tooLong)).isInstanceOf(ApiException.class);

    verify(users, never()).passwordMatches(any(), any());
    // The timing work is still done, on a value BCrypt can accept.
    ArgumentCaptor<String> candidate = ArgumentCaptor.forClass(String.class);
    verify(encoder).matches(candidate.capture(), eq(DUMMY_HASH));
    assertThat(candidate.getValue().getBytes(StandardCharsets.UTF_8).length)
        .isLessThanOrEqualTo(72);
  }

  @Test
  void aMultiByteCharacterIsNeverCutInTwoWhenShortening() {
    AppUser agent = user("agent", null, true, Role.AGENT);
    when(users.findByUsername("agent")).thenReturn(Optional.of(agent));
    String tooLong = "é".repeat(50);

    assertThatThrownBy(() -> service.issue("agent", tooLong)).isInstanceOf(ApiException.class);

    ArgumentCaptor<String> candidate = ArgumentCaptor.forClass(String.class);
    verify(encoder).matches(candidate.capture(), eq(DUMMY_HASH));
    assertThat(candidate.getValue()).isEqualTo("é".repeat(36));
  }

  @Test
  void theRequestNeverPrintsThePassword() {
    assertThat(new TokenRequest("agent", "super-secret-password").toString())
        .doesNotContain("super-secret-password");
  }

  @Test
  void theResponseNeverPrintsTheToken() {
    assertThat(new TokenResponse("header.payload.signature", "Bearer", 900).toString())
        .doesNotContain("header.payload.signature");
  }
}
