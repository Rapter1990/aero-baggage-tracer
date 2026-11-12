package com.aerotrace.identity.audit;

/** The authentication events that are recorded. */
public enum AuthEventType {
  TOKEN_ISSUED,
  LOGIN_FAILED,
  USER_REGISTERED,
  KEY_ROTATED,
  KEY_ROTATION_REQUESTED
}
