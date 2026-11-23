package com.aerotrace.notification.constant;

import lombok.experimental.UtilityClass;

/** The messages the service answers with (they are part of the API contract). */
@UtilityClass
public class ErrorMessages {

  /** Also the answer for another user's notification: existence is never leaked. */
  public static final String NOT_FOUND = "Notification not found";

  public static final String AUTHENTICATION_REQUIRED = "Authentication is required";
  public static final String INSUFFICIENT_PERMISSIONS = "You do not have permission to do this";

  /** The validation message of the {@code size} parameter. */
  public static final String SIZE_RULE =
      "size must be between 1 and " + NotificationRules.MAX_PAGE_SIZE;
}
