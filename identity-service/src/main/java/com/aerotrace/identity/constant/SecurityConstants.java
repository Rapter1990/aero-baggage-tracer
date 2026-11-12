package com.aerotrace.identity.constant;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/** Header values and cache settings used on authentication responses. */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class SecurityConstants {

  public static final String TOKEN_TYPE = "Bearer";
  public static final String WWW_AUTHENTICATE_BEARER = "Bearer";
  public static final String PRAGMA_HEADER = "Pragma";
  public static final String PRAGMA_NO_CACHE = "no-cache";

  /** How long gateways and caches may keep the published key set. */
  public static final long JWKS_MAX_AGE_SECONDS = 60;
}
