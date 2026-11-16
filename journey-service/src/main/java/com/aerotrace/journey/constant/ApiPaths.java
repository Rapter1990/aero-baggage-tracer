package com.aerotrace.journey.constant;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/** Every URL path this service answers on, in one place. */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class ApiPaths {

  public static final String BAGS = "/api/v1/bags";
  public static final String JOURNEY = BAGS + "/{bagTag}/journey";
  public static final String JOURNEY_ALL = BAGS + "/*/journey";
  public static final String SCANS = "/api/v1/scans";

  public static final String API_DOCS = "/v3/api-docs";
  public static final String API_DOCS_ALL = "/v3/api-docs/**";
  public static final String API_DOCS_YAML = "/v3/api-docs.yaml";

  public static final String HEALTH = "/actuator/health";
  public static final String HEALTH_ALL = "/actuator/health/**";
  public static final String INFO = "/actuator/info";
}
