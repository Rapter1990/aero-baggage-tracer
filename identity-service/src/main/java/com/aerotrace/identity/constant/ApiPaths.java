package com.aerotrace.identity.constant;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/** Every URL path this service answers on, in one place. */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class ApiPaths {

  public static final String AUTH_BASE = "/api/v1/auth";
  public static final String TOKEN = AUTH_BASE + "/token";
  public static final String ME = AUTH_BASE + "/me";
  public static final String POLICY = AUTH_BASE + "/policy";

  /** Fixed by the JWK Set standard's discovery convention; the gateway is configured with it. */
  public static final String JWKS = "/.well-known/jwks.json";

  public static final String API_DOCS = "/v3/api-docs";
  public static final String API_DOCS_ALL = "/v3/api-docs/**";
  public static final String API_DOCS_YAML = "/v3/api-docs.yaml";

  public static final String HEALTH = "/actuator/health";
  public static final String HEALTH_ALL = "/actuator/health/**";
  public static final String INFO = "/actuator/info";
}
