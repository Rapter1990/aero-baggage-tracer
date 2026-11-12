package com.aerotrace.identity.auth;

import com.aerotrace.common.error.ApiException;
import com.aerotrace.common.error.ErrorCode;
import com.aerotrace.common.security.Role;
import com.aerotrace.identity.audit.AuthAuditLog;
import com.aerotrace.identity.audit.AuthEventType;
import com.aerotrace.identity.audit.AuthOutcome;
import com.aerotrace.identity.constant.ApiMessages;
import com.aerotrace.identity.constant.AuthConstants;
import com.aerotrace.identity.constant.MetricNames;
import com.aerotrace.identity.constant.SecurityConstants;
import com.aerotrace.identity.model.domain.AppUser;
import com.aerotrace.identity.model.dto.request.ForgotPasswordRequest;
import com.aerotrace.identity.model.dto.request.LoginRequest;
import com.aerotrace.identity.model.dto.request.LogoutRequest;
import com.aerotrace.identity.model.dto.request.RefreshTokenRequest;
import com.aerotrace.identity.model.dto.request.RegisterRequest;
import com.aerotrace.identity.model.dto.request.ResetPasswordRequest;
import com.aerotrace.identity.model.dto.response.ForgotPasswordResponse;
import com.aerotrace.identity.model.dto.response.LogoutResponse;
import com.aerotrace.identity.model.dto.response.RegisteredUserResponse;
import com.aerotrace.identity.model.dto.response.TokenResponse;
import com.aerotrace.identity.model.entity.PasswordResetTokenEntity;
import com.aerotrace.identity.model.entity.RefreshTokenEntity;
import com.aerotrace.identity.model.mapper.IdentityMapper;
import com.aerotrace.identity.policy.AuthorizationPolicy;
import com.aerotrace.identity.policy.Permission;
import com.aerotrace.identity.repository.AppUserRepository;
import com.aerotrace.identity.repository.PasswordResetTokenRepository;
import com.aerotrace.identity.repository.RefreshTokenRepository;
import com.aerotrace.identity.security.jwt.JwtTokenService;
import com.aerotrace.identity.user.UserService;
import com.aerotrace.identity.util.TokenHashUtils;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Registration, sign-in, token refresh, password reset and sign-out.
 *
 * <p>Design rules, all of them about not helping an attacker:
 *
 * <ul>
 *   <li>A failed sign-in always looks the same (unknown user, wrong password, disabled account),
 *       takes the same work, and is audited.
 *   <li>Refresh and reset tokens are random, stored only as hashes, and used once. Presenting a
 *       refresh token that was rotated away a while ago is treated as theft and revokes every
 *       session of that user; a retry within seconds, or a token revoked by logout, is only
 *       refused.
 *   <li>Asking for a password reset returns the same message whether or not the account exists (but
 *       only an existing account gets a token).
 *   <li>Nothing secret is logged, counted or put in a span: only the outcome is a metric label.
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class AuthService {

  private final UserService users;
  private final AppUserRepository userRepository;
  private final RefreshTokenRepository refreshTokens;
  private final PasswordResetTokenRepository resetTokens;
  private final JwtTokenService jwtTokens;
  private final AuthorizationPolicy policy;
  private final AuthAuditLog audit;
  private final AuthProperties properties;
  private final IdentityMapper mapper;
  private final MeterRegistry meters;
  private final Clock clock;
  private final TransactionOperations transactions;

  /** Who is calling: anonymous, or a signed-in user with these roles. */
  public record Caller(boolean authenticated, Set<Role> roles) {
    public Caller {
      roles = Set.copyOf(roles);
    }

    public static Caller anonymous() {
      return new Caller(false, Set.of());
    }
  }

  /**
   * Registers an account. Anyone may register a passenger. A staff role needs a caller whose roles
   * allow {@link Permission#USER_MANAGE}.
   */
  public RegisteredUserResponse register(RegisterRequest request, Caller caller) {
    Role role = request.role() == null ? Role.PASSENGER : request.role();
    if (role != Role.PASSENGER && !policy.isAllowed(Permission.USER_MANAGE, caller.roles())) {
      throw caller.authenticated()
          ? new ApiException(ErrorCode.ACCESS_DENIED, ApiMessages.INSUFFICIENT_PERMISSIONS)
          : new ApiException(ErrorCode.UNAUTHENTICATED, ApiMessages.AUTHENTICATION_REQUIRED);
    }
    AppUser created =
        users.create(
            request.username(),
            request.email(),
            request.fullName(),
            request.password(),
            Set.of(role));
    audit.record(
        AuthEventType.USER_REGISTERED, AuthOutcome.SUCCESS, created.username(), created.id());
    return mapper.toRegisteredUserResponse(created);
  }

  /** Checks the credentials and issues an access token and a refresh token. */
  public TokenResponse login(LoginRequest request) {
    Optional<AppUser> found = users.findByUsernameOrEmail(request.usernameOrEmail());
    if (!users.authenticates(found, request.password())) {
      // The typed text is only kept when it names a real account: people paste passwords and
      // email addresses into that box, and the audit trail can never be cleaned up.
      audit.record(
          AuthEventType.LOGIN_FAILED,
          AuthOutcome.FAILURE,
          found.map(AppUser::username).orElse(null),
          found.map(AppUser::id).orElse(null));
      count(MetricNames.OUTCOME_FAILURE);
      throw new ApiException(ErrorCode.UNAUTHENTICATED, ApiMessages.INVALID_CREDENTIALS);
    }
    AppUser user = found.get();
    TokenResponse tokens = issueTokens(user);
    audit.record(AuthEventType.TOKEN_ISSUED, AuthOutcome.SUCCESS, user.username(), user.id());
    count(MetricNames.OUTCOME_SUCCESS);
    return tokens;
  }

  /**
   * Exchanges a refresh token for a new pair. The old token is revoked. If it was already revoked,
   * someone is replaying a stolen or copied token: every session of that user is ended.
   *
   * <p>No rollback on the failure paths, so the revocation done before the error is kept.
   */
  @Transactional(noRollbackFor = ApiException.class)
  public TokenResponse refreshToken(RefreshTokenRequest request) {
    Instant now = clock.instant();
    RefreshTokenEntity stored =
        refreshTokens
            .findByTokenHash(TokenHashUtils.sha256(request.refreshToken()))
            .orElseThrow(AuthService::invalidRefreshToken);
    AppUser user = mapper.toDomain(stored.getUser());

    if (stored.isExpired(now) || !user.enabled()) {
      throw invalidRefreshToken();
    }
    if (stored.isRevoked()) {
      boolean rotatedLongAgo =
          AuthConstants.REVOKED_ROTATED.equals(stored.getRevokedReason())
              && stored.getRevokedAt().plus(AuthConstants.REFRESH_REUSE_GRACE).isBefore(now);
      if (rotatedLongAgo) {
        // A token that was already exchanged is back: a copy of it is in someone else's hands.
        refreshTokens.revokeAllActiveForUser(user.id(), now, AuthConstants.REVOKED_REUSE);
        audit.record(
            AuthEventType.REFRESH_REUSE_DETECTED, AuthOutcome.FAILURE, user.username(), user.id());
      }
      // Logged out, reset, or rotated moments ago (a retry or a second tab): refused, no more.
      throw invalidRefreshToken();
    }
    if (refreshTokens.revoke(stored.getId(), now, AuthConstants.REVOKED_ROTATED) == 0) {
      // Another request used this token between our read and our update.
      throw invalidRefreshToken();
    }
    TokenResponse tokens = issueTokens(user);
    audit.record(AuthEventType.TOKEN_REFRESHED, AuthOutcome.SUCCESS, user.username(), user.id());
    return tokens;
  }

  /**
   * Starts a password reset. The reset token is returned in the answer. The message is the same
   * whether or not the account exists, but only an existing account gets a token.
   */
  @Transactional
  public ForgotPasswordResponse forgotPassword(ForgotPasswordRequest request) {
    Optional<AppUser> found =
        users.findByUsernameOrEmail(request.usernameOrEmail()).filter(AppUser::enabled);
    if (found.isEmpty()) {
      return new ForgotPasswordResponse(ApiMessages.PASSWORD_RESET_STARTED, null, null);
    }
    AppUser user = found.get();
    Instant now = clock.instant();
    Instant expiresAt = now.plus(properties.passwordResetTtl());
    String token = TokenHashUtils.randomToken();

    resetTokens.invalidateAllForUser(user.id(), now);
    resetTokens.save(
        new PasswordResetTokenEntity(
            userRepository.getReferenceById(user.id()),
            TokenHashUtils.sha256(token),
            expiresAt,
            now));
    audit.record(
        AuthEventType.PASSWORD_RESET_REQUESTED, AuthOutcome.SUCCESS, user.username(), user.id());

    return new ForgotPasswordResponse(ApiMessages.PASSWORD_RESET_STARTED, token, expiresAt);
  }

  /**
   * Sets a new password with a reset token (once) and ends every session of the user. The password
   * is hashed before the transaction opens, so the slow hash does not hold a connection or a lock.
   */
  public LogoutResponse resetPassword(ResetPasswordRequest request) {
    users.requireFitsBcrypt(request.newPassword());
    String newHash = users.encode(request.newPassword());
    return transactions.execute(status -> applyReset(request.resetToken(), newHash));
  }

  private LogoutResponse applyReset(String resetToken, String newHash) {
    Instant now = clock.instant();
    PasswordResetTokenEntity stored =
        resetTokens
            .findByTokenHash(TokenHashUtils.sha256(resetToken))
            .orElseThrow(AuthService::invalidResetToken);
    AppUser user = mapper.toDomain(stored.getUser());

    if (stored.isUsed() || stored.isExpired(now) || !user.enabled()) {
      throw invalidResetToken();
    }
    if (resetTokens.markUsed(stored.getId(), now) == 0) {
      throw invalidResetToken();
    }
    users.changePasswordHash(user.id(), newHash);
    refreshTokens.revokeAllActiveForUser(user.id(), now, AuthConstants.REVOKED_RESET);
    audit.record(AuthEventType.PASSWORD_RESET, AuthOutcome.SUCCESS, user.username(), user.id());
    return new LogoutResponse(ApiMessages.PASSWORD_RESET_DONE);
  }

  /** Revokes a refresh token. Unknown or already revoked tokens are not an error. */
  @Transactional
  public LogoutResponse logout(LogoutRequest request) {
    refreshTokens
        .findByTokenHash(TokenHashUtils.sha256(request.refreshToken()))
        .ifPresent(
            stored -> {
              AppUser user = mapper.toDomain(stored.getUser());
              if (refreshTokens.revoke(
                      stored.getId(), clock.instant(), AuthConstants.REVOKED_LOGOUT)
                  == 1) {
                audit.record(
                    AuthEventType.LOGGED_OUT, AuthOutcome.SUCCESS, user.username(), user.id());
              }
            });
    return new LogoutResponse(ApiMessages.LOGOUT_DONE);
  }

  private TokenResponse issueTokens(AppUser user) {
    Instant now = clock.instant();
    List<String> roles = user.roles().stream().map(Role::name).sorted().toList();
    String accessToken = jwtTokens.generateToken(user.id().toString(), roles, user.passengerRef());
    String refreshToken = TokenHashUtils.randomToken();
    Instant refreshExpiresAt = now.plus(properties.refreshTokenTtl());
    refreshTokens.save(
        new RefreshTokenEntity(
            userRepository.getReferenceById(user.id()),
            TokenHashUtils.sha256(refreshToken),
            refreshExpiresAt,
            now));
    return new TokenResponse(
        accessToken,
        refreshToken,
        SecurityConstants.TOKEN_TYPE,
        now,
        now.plus(jwtTokens.ttl()),
        refreshExpiresAt,
        roles);
  }

  private static ApiException invalidRefreshToken() {
    return new ApiException(ErrorCode.UNAUTHENTICATED, ApiMessages.INVALID_REFRESH_TOKEN);
  }

  private static ApiException invalidResetToken() {
    return new ApiException(ErrorCode.VALIDATION_ERROR, ApiMessages.INVALID_RESET_TOKEN);
  }

  private void count(String outcome) {
    meters.counter(MetricNames.TOKEN_REQUESTS, MetricNames.TAG_OUTCOME, outcome).increment();
  }
}
