package com.aerotrace.identity.constant;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/** Token and key settings: defaults, key formats and header values. */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class JwtConstants {

  public static final String PROPERTY_PREFIX = "aerotrace.identity.jwt";

  /**
   * A development key pair that ships inside the service so it starts with nothing configured.
   * Everyone who can read the repository has its private key: never rely on it where the tokens
   * matter.
   */
  public static final String DEFAULT_PRIVATE_KEY_LOCATION = "classpath:keys/private.pem";

  public static final String DEFAULT_PUBLIC_KEY_LOCATION = "classpath:keys/public.pem";

  public static final String DEFAULT_AUDIENCE = "aerotrace-api";
  public static final String DEFAULT_TTL = "15m";
  public static final String DEFAULT_CLOCK_SKEW = "1m";

  /** RSA with SHA-256, the algorithm the gateway's decoder accepts. */
  public static final String SIGNATURE_ALGORITHM = "RS256";

  public static final String HEADER_TYPE = "JWT";
}
