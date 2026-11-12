package com.aerotrace.identity.constant;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/** Settings of the refresh-token and password-reset flows. */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class AuthConstants {

  public static final String PROPERTY_PREFIX = "aerotrace.identity.auth";

  public static final String DEFAULT_REFRESH_TOKEN_TTL = "7d";
  public static final String DEFAULT_PASSWORD_RESET_TTL = "15m";

  /** A refresh or reset token is 48 random bytes (384 bits), base64url without padding. */
  public static final int RANDOM_TOKEN_BYTES = 48;

  /**
   * A just-rotated refresh token presented again within this time is a client retry or a second
   * tab, not theft: it is refused, but the user's sessions are left alone.
   */
  public static final java.time.Duration REFRESH_REUSE_GRACE = java.time.Duration.ofSeconds(10);

  /** Why a refresh token was revoked. */
  public static final String REVOKED_ROTATED = "ROTATED";

  public static final String REVOKED_LOGOUT = "LOGOUT";
  public static final String REVOKED_RESET = "RESET";
  public static final String REVOKED_REUSE = "REUSE";

  /** Expired token rows are kept this long (for diagnosis), then deleted. */
  public static final java.time.Duration TOKEN_RETENTION = java.time.Duration.ofDays(1);

  public static final String DEFAULT_PURGE_INTERVAL = "1h";

  public static final String TOKEN_HASH_ALGORITHM = "SHA-256";

  public static final String STATUS_ACTIVE = "ACTIVE";
  public static final String STATUS_DISABLED = "DISABLED";
}
