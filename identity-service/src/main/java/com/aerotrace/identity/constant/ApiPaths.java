package com.aerotrace.identity.constant;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/** Every URL path this service answers on, in one place. */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class ApiPaths {

  public static final String AUTH_BASE = "/api/v1/auth";
  public static final String REGISTER = AUTH_BASE + "/register";
  public static final String LOGIN = AUTH_BASE + "/login";
  public static final String REFRESH_TOKEN = AUTH_BASE + "/refresh-token";
  public static final String FORGOT_PASSWORD = AUTH_BASE + "/forgot-password";
  public static final String RESET_PASSWORD = AUTH_BASE + "/reset-password";
  public static final String LOGOUT = AUTH_BASE + "/logout";

  public static final String API_DOCS = "/v3/api-docs";
  public static final String API_DOCS_ALL = "/v3/api-docs/**";
  public static final String API_DOCS_YAML = "/v3/api-docs.yaml";

  public static final String HEALTH = "/actuator/health";
  public static final String HEALTH_ALL = "/actuator/health/**";
  public static final String INFO = "/actuator/info";

  /**
   * Calls made without an access token: nobody has one yet, or they are proving who they are by
   * other means (a password, a refresh token, a reset token).
   */
  public static final String[] PUBLIC_AUTH_CALLS = {
    LOGIN, REFRESH_TOKEN, FORGOT_PASSWORD, RESET_PASSWORD, LOGOUT
  };
}
