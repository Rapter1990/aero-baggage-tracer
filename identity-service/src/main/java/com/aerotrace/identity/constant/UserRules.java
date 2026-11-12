package com.aerotrace.identity.constant;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/** The rules for usernames, emails and passwords. */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class UserRules {

  /** Letters, digits, dot, underscore and hyphen; matched without regard to case. */
  public static final String USERNAME_PATTERN = "(?i)[a-z0-9._-]{3,64}";

  public static final int MAX_USERNAME_LENGTH = 64;
  public static final int MAX_EMAIL_LENGTH = 150;
  public static final int MIN_FULL_NAME_LENGTH = 2;
  public static final int MAX_FULL_NAME_LENGTH = 150;

  /** A passenger's pseudonymous reference: "P-" and 20 digits. It carries no personal data. */
  public static final String PASSENGER_REF_PREFIX = "P-";

  public static final int PASSENGER_REF_DIGITS = 20;
  public static final String PASSENGER_REF_PATTERN = "P-[0-9]{20}";

  public static final int MIN_PASSWORD_LENGTH = 12;

  /** BCrypt silently ignores everything past 72 bytes, so longer passwords are refused. */
  public static final int MAX_PASSWORD_BYTES = 72;

  /** Upper bound on what a request may carry, so oversized input never reaches the hash. */
  public static final int MAX_CREDENTIAL_LENGTH = 256;
}
