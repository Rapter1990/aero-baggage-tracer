package com.aerotrace.identity.audit;

/** The authentication events that are recorded. */
public enum AuthEventType {
  USER_REGISTERED,
  TOKEN_ISSUED,
  LOGIN_FAILED,
  LOGIN_LOCKED,
  PASSWORD_RESET_LOCKED,
  TOKEN_REFRESHED,
  REFRESH_REUSE_DETECTED,
  PASSWORD_RESET_REQUESTED,
  PASSWORD_RESET,
  LOGGED_OUT
}
