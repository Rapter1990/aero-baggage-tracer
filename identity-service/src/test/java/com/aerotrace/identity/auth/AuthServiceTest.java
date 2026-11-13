package com.aerotrace.identity.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aerotrace.common.error.ApiException;
import com.aerotrace.common.error.ErrorCode;
import com.aerotrace.common.security.Role;
import com.aerotrace.identity.audit.AuthAuditLog;
import com.aerotrace.identity.audit.AuthEventType;
import com.aerotrace.identity.audit.AuthOutcome;
import com.aerotrace.identity.base.AbstractBaseServiceTest;
import com.aerotrace.identity.model.domain.AppUser;
import com.aerotrace.identity.model.dto.request.ForgotPasswordRequest;
import com.aerotrace.identity.model.dto.request.LoginRequest;
import com.aerotrace.identity.model.dto.request.LogoutRequest;
import com.aerotrace.identity.model.dto.request.RefreshTokenRequest;
import com.aerotrace.identity.model.dto.request.RegisterRequest;
import com.aerotrace.identity.model.dto.request.ResetPasswordRequest;
import com.aerotrace.identity.model.dto.response.ForgotPasswordResponse;
import com.aerotrace.identity.model.dto.response.RegisteredUserResponse;
import com.aerotrace.identity.model.dto.response.TokenResponse;
import com.aerotrace.identity.model.entity.AppUserEntity;
import com.aerotrace.identity.model.entity.PasswordResetTokenEntity;
import com.aerotrace.identity.model.entity.RefreshTokenEntity;
import com.aerotrace.identity.model.mapper.IdentityMapperImpl;
import com.aerotrace.identity.policy.AuthorizationPolicy;
import com.aerotrace.identity.repository.AppUserRepository;
import com.aerotrace.identity.repository.PasswordResetTokenRepository;
import com.aerotrace.identity.repository.RefreshTokenRepository;
import com.aerotrace.identity.security.jwt.JwtTokenService;
import com.aerotrace.identity.support.MutableClock;
import com.aerotrace.identity.user.UserService;
import com.aerotrace.identity.util.TokenHashUtils;
import io.jsonwebtoken.JwtException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.springframework.transaction.support.TransactionOperations;

class AuthServiceTest extends AbstractBaseServiceTest {

  private static final Instant NOW = Instant.parse("2026-11-12T10:00:00Z");
  private static final String PASSWORD = "correct-horse-battery";

  @Mock private UserService users;
  @Mock private AppUserRepository userRepository;
  @Mock private RefreshTokenRepository refreshTokens;
  @Mock private PasswordResetTokenRepository resetTokens;
  @Mock private JwtTokenService jwtTokens;
  @Mock private AuthAuditLog audit;

  private final MutableClock clock = new MutableClock(NOW);
  private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
  private AuthProperties properties;
  private AuthService service;
  private AppUser jane;
  private AppUserEntity janeEntity;

  @BeforeEach
  void setUp() {
    properties = new AuthProperties(Duration.ofDays(7), Duration.ofMinutes(15));
    service = newService(properties);
    UUID id = UUID.randomUUID();
    jane =
        new AppUser(
            id,
            "jane",
            "jane@example.com",
            "Jane Doe",
            "hash",
            "P-00000000000000000001",
            true,
            Set.of(Role.PASSENGER),
            NOW);
    janeEntity =
        new AppUserEntity(
            id,
            "jane",
            "jane@example.com",
            "Jane Doe",
            "hash",
            "P-00000000000000000001",
            Set.of(Role.PASSENGER));
    when(jwtTokens.generateToken(anyString(), any(), any())).thenReturn("signed.jwt.token");
    when(jwtTokens.ttl()).thenReturn(Duration.ofMinutes(15));
    when(jwtTokens.generateRefreshToken(anyString(), any()))
        .thenAnswer(invocation -> TokenHashUtils.randomToken());
    when(userRepository.getReferenceById(id)).thenReturn(janeEntity);
    when(refreshTokens.revoke(any(), any(), any())).thenReturn(1);
    when(resetTokens.markUsed(any(), any())).thenReturn(1);
    when(users.encode(any())).thenReturn("new-hash");
  }

  private AuthService newService(AuthProperties props) {
    return new AuthService(
        users,
        userRepository,
        refreshTokens,
        resetTokens,
        jwtTokens,
        new AuthorizationPolicy(),
        audit,
        props,
        new IdentityMapperImpl(),
        meters,
        clock,
        TransactionOperations.withoutTransaction());
  }

  private static RegisterRequest register(Role role) {
    return new RegisterRequest("jane", "jane@example.com", PASSWORD, "Jane Doe", role);
  }

  private static void assertFails(Runnable call, ErrorCode code) {
    assertThatThrownBy(call::run)
        .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.errorCode()).isEqualTo(code));
  }

  // ---- register ----

  @Test
  void shouldRegisterAPassengerWithoutAnyToken() {
    when(users.create(any(), any(), any(), any(), any())).thenReturn(jane);

    RegisteredUserResponse response =
        service.register(register(null), AuthService.Caller.anonymous());

    assertThat(response.username()).isEqualTo("jane");
    assertThat(response.roles()).containsExactly("PASSENGER");
    verify(users).create("jane", "jane@example.com", "Jane Doe", PASSWORD, Set.of(Role.PASSENGER));
    verify(audit).record(AuthEventType.USER_REGISTERED, AuthOutcome.SUCCESS, "jane", jane.id());
  }

  @Test
  void shouldAskAnAnonymousCallerForAStaffRoleToSignIn() {
    assertFails(
        () -> service.register(register(Role.ADMIN), AuthService.Caller.anonymous()),
        ErrorCode.UNAUTHENTICATED);
    verify(users, never()).create(any(), any(), any(), any(), any());
  }

  @Test
  void shouldRefuseAStaffRoleToACallerWhoIsNotAnAdmin() {
    AuthService.Caller agent = new AuthService.Caller(true, Set.of(Role.AGENT));

    assertFails(() -> service.register(register(Role.DISPATCHER), agent), ErrorCode.ACCESS_DENIED);
    verify(users, never()).create(any(), any(), any(), any(), any());
  }

  @Test
  void shouldLetAnAdminRegisterStaff() {
    AppUser agent =
        new AppUser(
            UUID.randomUUID(),
            "jane",
            "j@example.com",
            "Jane",
            "hash",
            null,
            true,
            Set.of(Role.AGENT),
            NOW);
    when(users.create(any(), any(), any(), any(), eq(Set.of(Role.AGENT)))).thenReturn(agent);

    RegisteredUserResponse response =
        service.register(register(Role.AGENT), new AuthService.Caller(true, Set.of(Role.ADMIN)));

    assertThat(response.roles()).containsExactly("AGENT");
  }

  // ---- login ----

  @Test
  void shouldLogInAndIssueATokenPairWhoseRefreshTokenIsStoredHashed() {
    when(users.findByUsernameOrEmail("jane")).thenReturn(Optional.of(jane));
    when(users.authenticates(any(), eq(PASSWORD))).thenReturn(true);

    TokenResponse tokens = service.login(new LoginRequest("jane", PASSWORD));

    assertThat(tokens.accessToken()).isEqualTo("signed.jwt.token");
    assertThat(tokens.tokenType()).isEqualTo("Bearer");
    assertThat(tokens.roles()).containsExactly("PASSENGER");
    assertThat(tokens.issuedAt()).isEqualTo(NOW);
    assertThat(tokens.accessTokenExpiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(15)));
    assertThat(tokens.refreshTokenExpiresAt()).isEqualTo(NOW.plus(Duration.ofDays(7)));
    ArgumentCaptor<RefreshTokenEntity> stored = ArgumentCaptor.forClass(RefreshTokenEntity.class);
    verify(refreshTokens).save(stored.capture());
    assertThat(stored.getValue().getTokenHash())
        .isEqualTo(TokenHashUtils.sha256(tokens.refreshToken()))
        .isNotEqualTo(tokens.refreshToken());
    verify(audit).record(AuthEventType.TOKEN_ISSUED, AuthOutcome.SUCCESS, "jane", jane.id());
    assertThat(meters.counter("identity.auth.token.requests", "outcome", "success").count())
        .isEqualTo(1);
  }

  @Test
  void shouldGiveTheSameFailureForAnUnknownUserAndAWrongPassword() {
    when(users.findByUsernameOrEmail("ghost")).thenReturn(Optional.empty());
    when(users.findByUsernameOrEmail("jane")).thenReturn(Optional.of(jane));
    when(users.authenticates(any(), any())).thenReturn(false);

    for (String name : new String[] {"ghost", "jane"}) {
      assertThatThrownBy(() -> service.login(new LoginRequest(name, "wrong-password-1")))
          .isInstanceOfSatisfying(
              ApiException.class,
              e -> {
                assertThat(e.errorCode()).isEqualTo(ErrorCode.UNAUTHENTICATED);
                assertThat(e.getMessage()).isEqualTo("Invalid credentials.");
              });
    }
    verify(audit).record(AuthEventType.LOGIN_FAILED, AuthOutcome.FAILURE, null, null);
    verify(audit).record(AuthEventType.LOGIN_FAILED, AuthOutcome.FAILURE, "jane", jane.id());
    verify(refreshTokens, never()).save(any());
    assertThat(meters.counter("identity.auth.token.requests", "outcome", "failure").count())
        .isEqualTo(2);
  }

  // ---- refresh ----

  private String issueRefreshToken(boolean revoked, Instant expiresAt) {
    return issueRevokedRefreshToken(revoked ? "ROTATED" : null, NOW.minusSeconds(60), expiresAt);
  }

  private String issueRevokedRefreshToken(String reason, Instant revokedAt, Instant expiresAt) {
    String raw = TokenHashUtils.randomToken();
    RefreshTokenEntity stored =
        new RefreshTokenEntity(janeEntity, TokenHashUtils.sha256(raw), expiresAt, NOW);
    if (reason != null) {
      org.springframework.test.util.ReflectionTestUtils.setField(stored, "revokedAt", revokedAt);
      org.springframework.test.util.ReflectionTestUtils.setField(stored, "revokedReason", reason);
    }
    when(refreshTokens.findByTokenHash(TokenHashUtils.sha256(raw))).thenReturn(Optional.of(stored));
    return raw;
  }

  @Test
  void shouldRotateARefreshTokenAndIssueANewPair() {
    String raw = issueRefreshToken(false, NOW.plusSeconds(60));

    TokenResponse tokens = service.refreshToken(new RefreshTokenRequest(raw));

    assertThat(tokens.refreshToken()).isNotEqualTo(raw);
    verify(refreshTokens).revoke(any(), eq(NOW), eq("ROTATED"));
    verify(refreshTokens).save(any(RefreshTokenEntity.class));
    verify(audit).record(AuthEventType.TOKEN_REFRESHED, AuthOutcome.SUCCESS, "jane", jane.id());
  }

  @Test
  void shouldRefuseAForgedOrExpiredRefreshTokenWithoutAskingTheDatabase() {
    when(jwtTokens.parseRefreshClaims(any())).thenThrow(new JwtException("bad signature"));

    assertFails(
        () -> service.refreshToken(new RefreshTokenRequest("not.a.jwt")),
        ErrorCode.UNAUTHENTICATED);

    verify(refreshTokens, never()).findByTokenHash(any());
    verify(refreshTokens, never()).save(any());
  }

  @Test
  void shouldRefuseAnUnknownRefreshToken() {
    when(refreshTokens.findByTokenHash(any())).thenReturn(Optional.empty());

    assertFails(
        () -> service.refreshToken(new RefreshTokenRequest("nope")), ErrorCode.UNAUTHENTICATED);
    verify(refreshTokens, never()).save(any());
  }

  @Test
  void shouldRefuseAnExpiredRefreshTokenWithoutRevokingAnythingElse() {
    String raw = issueRefreshToken(false, NOW);

    assertFails(
        () -> service.refreshToken(new RefreshTokenRequest(raw)), ErrorCode.UNAUTHENTICATED);
    verify(refreshTokens, never()).revokeAllActiveForUser(any(), any(), any());
  }

  @Test
  void shouldTreatAReusedRefreshTokenAsTheftAndEndEverySession() {
    String raw = issueRefreshToken(true, NOW.plusSeconds(60));

    assertFails(
        () -> service.refreshToken(new RefreshTokenRequest(raw)), ErrorCode.UNAUTHENTICATED);

    verify(refreshTokens).revokeAllActiveForUser(jane.id(), NOW, "REUSE");
    verify(audit)
        .record(AuthEventType.REFRESH_REUSE_DETECTED, AuthOutcome.FAILURE, "jane", jane.id());
    verify(refreshTokens, never()).save(any());
  }

  @Test
  void shouldOnlyRefuseARetryOfAJustRotatedToken() {
    String raw = issueRevokedRefreshToken("ROTATED", NOW.minusSeconds(3), NOW.plusSeconds(60));

    assertFails(
        () -> service.refreshToken(new RefreshTokenRequest(raw)), ErrorCode.UNAUTHENTICATED);

    verify(refreshTokens, never()).revokeAllActiveForUser(any(), any(), any());
    verify(audit, never()).record(eq(AuthEventType.REFRESH_REUSE_DETECTED), any(), any(), any());
  }

  @Test
  void shouldNotTreatATokenRevokedByLogoutOrResetAsTheft() {
    for (String reason : new String[] {"LOGOUT", "RESET", "REUSE"}) {
      String raw = issueRevokedRefreshToken(reason, NOW.minusSeconds(3600), NOW.plusSeconds(60));

      assertFails(
          () -> service.refreshToken(new RefreshTokenRequest(raw)), ErrorCode.UNAUTHENTICATED);
    }
    verify(refreshTokens, never()).revokeAllActiveForUser(any(), any(), any());
  }

  @Test
  void shouldCheckExpiryBeforeReuseSoAnOldStolenTokenCannotKeepKillingSessions() {
    String raw = issueRevokedRefreshToken("ROTATED", NOW.minusSeconds(3600), NOW.minusSeconds(1));

    assertFails(
        () -> service.refreshToken(new RefreshTokenRequest(raw)), ErrorCode.UNAUTHENTICATED);

    verify(refreshTokens, never()).revokeAllActiveForUser(any(), any(), any());
  }

  @Test
  void shouldRefuseWhenAnotherRequestUsedTheTokenFirst() {
    String raw = issueRefreshToken(false, NOW.plusSeconds(60));
    when(refreshTokens.revoke(any(), any(), any())).thenReturn(0);

    assertFails(
        () -> service.refreshToken(new RefreshTokenRequest(raw)), ErrorCode.UNAUTHENTICATED);
    verify(refreshTokens, never()).save(any());
  }

  @Test
  void shouldRefuseARefreshTokenOfADisabledUser() {
    org.springframework.test.util.ReflectionTestUtils.setField(janeEntity, "enabled", false);
    String raw = issueRefreshToken(false, NOW.plusSeconds(60));

    assertFails(
        () -> service.refreshToken(new RefreshTokenRequest(raw)), ErrorCode.UNAUTHENTICATED);
  }

  // ---- forgot password ----

  @Test
  void shouldAnswerTheSameForAnUnknownAccountAndCreateNothing() {
    when(users.findByUsernameOrEmail("ghost")).thenReturn(Optional.empty());

    ForgotPasswordResponse response = service.forgotPassword(new ForgotPasswordRequest("ghost"));

    assertThat(response.message())
        .isEqualTo("If the account exists, a password reset has been started.");
    assertThat(response.resetToken()).isNull();
    verify(resetTokens, never()).save(any());
  }

  @Test
  void shouldReturnTheTokenAndStoreOnlyItsHash() {
    when(users.findByUsernameOrEmail("jane")).thenReturn(Optional.of(jane));

    ForgotPasswordResponse response = service.forgotPassword(new ForgotPasswordRequest("jane"));

    assertThat(response.resetToken()).hasSize(64);
    assertThat(response.expiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(15)));
    ArgumentCaptor<PasswordResetTokenEntity> stored =
        ArgumentCaptor.forClass(PasswordResetTokenEntity.class);
    verify(resetTokens).invalidateAllForUser(jane.id(), NOW);
    verify(resetTokens).save(stored.capture());
    assertThat(stored.getValue().getTokenHash())
        .isEqualTo(TokenHashUtils.sha256(response.resetToken()))
        .isNotEqualTo(response.resetToken());
    assertThat(stored.getValue().getExpiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(15)));
    verify(audit)
        .record(AuthEventType.PASSWORD_RESET_REQUESTED, AuthOutcome.SUCCESS, "jane", jane.id());
  }

  @Test
  void shouldNotStartAResetForADisabledAccount() {
    AppUser disabled =
        new AppUser(
            jane.id(),
            "jane",
            "jane@example.com",
            "Jane",
            "hash",
            null,
            false,
            Set.of(Role.AGENT),
            NOW);
    when(users.findByUsernameOrEmail("jane")).thenReturn(Optional.of(disabled));

    service.forgotPassword(new ForgotPasswordRequest("jane"));

    verify(resetTokens, never()).save(any());
  }

  // ---- reset password ----

  private String issueResetToken(boolean used, Instant expiresAt) {
    String raw = TokenHashUtils.randomToken();
    PasswordResetTokenEntity stored =
        new PasswordResetTokenEntity(janeEntity, TokenHashUtils.sha256(raw), expiresAt, NOW);
    if (used) {
      org.springframework.test.util.ReflectionTestUtils.setField(stored, "usedAt", NOW);
    }
    when(resetTokens.findByTokenHash(TokenHashUtils.sha256(raw))).thenReturn(Optional.of(stored));
    return raw;
  }

  @Test
  void shouldResetThePasswordAndEndEverySession() {
    String raw = issueResetToken(false, NOW.plusSeconds(60));

    var response = service.resetPassword(new ResetPasswordRequest(raw, "a-brand-new-password"));

    assertThat(response.message()).contains("reset successfully");
    verify(users).changePasswordHash(jane.id(), "new-hash");
    verify(refreshTokens).revokeAllActiveForUser(jane.id(), NOW, "RESET");
    verify(audit).record(AuthEventType.PASSWORD_RESET, AuthOutcome.SUCCESS, "jane", jane.id());
  }

  @Test
  void shouldRefuseAnUnknownUsedOrExpiredResetToken() {
    when(resetTokens.findByTokenHash(any())).thenReturn(Optional.empty());
    String used = issueResetToken(true, NOW.plusSeconds(60));
    String expired = issueResetToken(false, NOW);

    for (String token : new String[] {"unknown", used, expired}) {
      assertFails(
          () -> service.resetPassword(new ResetPasswordRequest(token, "a-brand-new-password")),
          ErrorCode.VALIDATION_ERROR);
    }
    verify(users, never()).changePasswordHash(any(), any());
  }

  @Test
  void shouldRefuseAResetTokenThatAnotherRequestJustUsed() {
    String raw = issueResetToken(false, NOW.plusSeconds(60));
    when(resetTokens.markUsed(any(), any())).thenReturn(0);

    assertFails(
        () -> service.resetPassword(new ResetPasswordRequest(raw, "a-brand-new-password")),
        ErrorCode.VALIDATION_ERROR);
    verify(users, never()).changePasswordHash(any(), any());
  }

  // ---- logout ----

  @Test
  void shouldRevokeTheRefreshTokenOnLogout() {
    String raw = issueRefreshToken(false, NOW.plusSeconds(60));

    var response = service.logout(new LogoutRequest(raw));

    assertThat(response.message()).isEqualTo("Logout completed successfully");
    verify(refreshTokens).revoke(any(), eq(NOW), eq("LOGOUT"));
    verify(audit).record(AuthEventType.LOGGED_OUT, AuthOutcome.SUCCESS, "jane", jane.id());
  }

  @Test
  void shouldSucceedOnLogoutOfAnUnknownToken() {
    when(refreshTokens.findByTokenHash(any())).thenReturn(Optional.empty());

    assertThat(service.logout(new LogoutRequest("unknown")).message())
        .isEqualTo("Logout completed successfully");
    verify(refreshTokens, never()).revoke(any(), any(), any());
  }
}
