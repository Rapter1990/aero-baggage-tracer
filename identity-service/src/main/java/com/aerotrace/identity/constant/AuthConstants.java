package com.aerotrace.identity.constant;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/** Settings of the refresh-token and password-reset flows. */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class AuthConstants {

  public static final String PROPERTY_PREFIX = "aerotrace.identity.auth";
  public static final String BOOTSTRAP_PROPERTY_PREFIX = "aerotrace.identity.bootstrap-admin";

  public static final String DEFAULT_REFRESH_TOKEN_TTL = "7d";
  public static final String DEFAULT_PASSWORD_RESET_TTL = "15m";

  /** A refresh or reset token is 48 random bytes (384 bits), base64url without padding. */
  public static final int RANDOM_TOKEN_BYTES = 48;

  public static final String TOKEN_HASH_ALGORITHM = "SHA-256";

  public static final String STATUS_ACTIVE = "ACTIVE";
  public static final String STATUS_DISABLED = "DISABLED";

  public static final String BOOTSTRAP_ADMIN_FULL_NAME = "Administrator";
}
