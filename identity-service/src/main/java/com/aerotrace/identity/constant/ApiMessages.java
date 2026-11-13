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
  public static final String INVALID_REFRESH_TOKEN = "Invalid or expired refresh token.";
  public static final String INVALID_RESET_TOKEN = "Invalid or expired password reset token.";

  public static final String MUST_NOT_BE_BLANK = "must not be blank";
  public static final String TOO_LONG =
      "must be at most " + UserRules.MAX_CREDENTIAL_LENGTH + " characters";
  public static final String TOKEN_TOO_LONG =
      "must be at most " + UserRules.MAX_TOKEN_LENGTH + " characters";
  public static final String MUST_BE_EMAIL = "must be a valid email address";
  public static final String USERNAME_RULE =
      "must be 3-64 characters of letters, digits, '.', '_' or '-'";
  public static final String PASSWORD_RULE =
      "must be at least "
          + UserRules.MIN_PASSWORD_LENGTH
          + " characters and at most "
          + UserRules.MAX_PASSWORD_BYTES
          + " bytes";
  public static final String FULL_NAME_RULE =
      "must be "
          + UserRules.MIN_FULL_NAME_LENGTH
          + "-"
          + UserRules.MAX_FULL_NAME_LENGTH
          + " characters";

  public static final String USER_ALREADY_EXISTS = "That username or email is already registered";
  public static final String ROLE_REQUIRED = "A user needs at least one role";

  public static final String PASSWORD_RESET_STARTED =
      "If the account exists, a password reset has been started.";
  public static final String PASSWORD_RESET_DONE =
      "Password was reset successfully. Active refresh tokens were revoked.";
  public static final String LOGOUT_DONE = "Logout completed successfully";
}
