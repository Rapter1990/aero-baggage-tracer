package com.aerotrace.identity.auth;

import com.aerotrace.identity.constant.AuthConstants;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings of the refresh-token, password-reset and lockout flows.
 *
 * @param refreshTokenTtl how long a refresh token can be exchanged
 * @param passwordResetTtl how long a password reset token can be used
 * @param maxFailedLogins failed sign-ins for one typed name, within {@code failureWindow}, that
 *     lock it
 * @param lockoutDuration how long a locked name is refused (a fixed time: further attempts do not
 *     extend it, so an attacker cannot keep a victim locked out by trying)
 * @param failureWindow failures further apart than this do not add up
 * @param maxPasswordResetRequests reset requests per typed name within {@code
 *     passwordResetRequestWindow} that are answered; the next one locks the name for reset requests
 * @param passwordResetRequestWindow the time over which reset requests are counted, and for which
 *     the name stays locked
 */
@ConfigurationProperties(AuthConstants.PROPERTY_PREFIX)
public record AuthProperties(
    @DefaultValue(AuthConstants.DEFAULT_REFRESH_TOKEN_TTL) Duration refreshTokenTtl,
    @DefaultValue(AuthConstants.DEFAULT_PASSWORD_RESET_TTL) Duration passwordResetTtl,
    @DefaultValue(AuthConstants.DEFAULT_MAX_FAILED_LOGINS) int maxFailedLogins,
    @DefaultValue(AuthConstants.DEFAULT_LOCKOUT_DURATION) Duration lockoutDuration,
    @DefaultValue(AuthConstants.DEFAULT_FAILURE_WINDOW) Duration failureWindow,
    @DefaultValue(AuthConstants.DEFAULT_MAX_RESET_REQUESTS) int maxPasswordResetRequests,
    @DefaultValue(AuthConstants.DEFAULT_RESET_REQUEST_WINDOW) Duration passwordResetRequestWindow) {

  public AuthProperties {
    if (maxFailedLogins < 1 || maxPasswordResetRequests < 1) {
      throw new IllegalArgumentException("The attempt limits must be at least 1");
    }
  }
}
