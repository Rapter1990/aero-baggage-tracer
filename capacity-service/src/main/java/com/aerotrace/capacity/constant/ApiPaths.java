package com.aerotrace.capacity.constant;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/** Every URL path this service answers on, in one place. */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class ApiPaths {

  public static final String RESERVATIONS = "/api/v1/capacity/reservations";
  public static final String RESERVATION = RESERVATIONS + "/{id}";
  public static final String RESERVATION_ALL = RESERVATIONS + "/*";
  public static final String CONFIRMATION = RESERVATION + "/confirmation";
  public static final String CONFIRMATION_ALL = RESERVATIONS + "/*/confirmation";

  public static final String RESOURCES = "/api/v1/capacity/resources";
  public static final String RESOURCE = RESOURCES + "/{id}";
  public static final String RESOURCE_ALL = RESOURCES + "/*";

  public static final String API_DOCS = "/v3/api-docs";
  public static final String API_DOCS_ALL = "/v3/api-docs/**";
  public static final String API_DOCS_YAML = "/v3/api-docs.yaml";

  public static final String HEALTH = "/actuator/health";
  public static final String HEALTH_ALL = "/actuator/health/**";
  public static final String INFO = "/actuator/info";
}
