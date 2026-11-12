package com.aerotrace.identity.auth;

import com.aerotrace.identity.constant.AuthConstants;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings of the refresh-token and password-reset flows.
 *
 * @param refreshTokenTtl how long a refresh token can be exchanged
 * @param passwordResetTtl how long a password reset token can be used
 * @param passwordResetReturnToken return the reset token in the forgot-password response. Only for
 *     development: there is no mail service yet, and in production the token must reach the user
 *     out of band, never the caller of the endpoint
 */
@ConfigurationProperties(AuthConstants.PROPERTY_PREFIX)
public record AuthProperties(
    @DefaultValue(AuthConstants.DEFAULT_REFRESH_TOKEN_TTL) Duration refreshTokenTtl,
    @DefaultValue(AuthConstants.DEFAULT_PASSWORD_RESET_TTL) Duration passwordResetTtl,
    @DefaultValue("false") boolean passwordResetReturnToken) {}
