package com.aerotrace.recovery.constant;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/** Every URL path this service answers on, in one place. */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class ApiPaths {

  public static final String CASES = "/api/v1/recovery-cases";
  public static final String TRANSITIONS = CASES + "/{id}/transitions";
  public static final String TRANSITIONS_ALL = CASES + "/*/transitions";
  public static final String ASSIGNMENT = CASES + "/{id}/assignment";
  public static final String ASSIGNMENT_ALL = CASES + "/*/assignment";

  public static final String POLICY = "/api/v1/recovery-policy";

  public static final String API_DOCS = "/v3/api-docs";
  public static final String API_DOCS_ALL = "/v3/api-docs/**";
  public static final String API_DOCS_YAML = "/v3/api-docs.yaml";

  public static final String HEALTH = "/actuator/health";
  public static final String HEALTH_ALL = "/actuator/health/**";
  public static final String INFO = "/actuator/info";
}
