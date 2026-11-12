package com.aerotrace.identity.constant;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * Texts that reach API clients. All are fixed: none contains anything the client sent, so none can
 * reflect a password, a username or a passenger reference.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class ApiMessages {

  public static final String INVALID_CREDENTIALS = "Invalid credentials.";
  public static final String AUTHENTICATION_REQUIRED = "Authentication required";
  public static final String INSUFFICIENT_PERMISSIONS = "Insufficient permissions";

  public static final String MUST_NOT_BE_BLANK = "must not be blank";
  public static final String TOO_LONG =
      "must be at most " + UserRules.MAX_CREDENTIAL_LENGTH + " characters";

  public static final String USER_NOT_REGISTERED = "The user could not be registered";
  public static final String USERNAME_RULE =
      "Username must be 3-64 characters of letters, digits, '.', '_' or '-'";
  public static final String PASSWORD_RULE =
      "Password must be at least "
          + UserRules.MIN_PASSWORD_LENGTH
          + " characters and at most "
          + UserRules.MAX_PASSWORD_BYTES
          + " bytes";
  public static final String ROLE_REQUIRED = "A user needs at least one role";
  public static final String PASSENGER_REF_REQUIRED =
      "A passenger needs a valid passenger reference";
  public static final String PASSENGER_REF_FORBIDDEN =
      "Only a passenger can carry a passenger reference";
}
