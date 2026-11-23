package com.aerotrace.notification.constant;

import lombok.experimental.UtilityClass;

/** Every URL path this service answers on, in one place. */
@UtilityClass
public class ApiPaths {

  public static final String NOTIFICATIONS = "/api/v1/notifications";
  public static final String NOTIFICATION = NOTIFICATIONS + "/{id}";
  public static final String NOTIFICATION_ANY = NOTIFICATIONS + "/*";

  public static final String API_DOCS = "/v3/api-docs";
  public static final String API_DOCS_ALL = "/v3/api-docs/**";
  public static final String API_DOCS_YAML = "/v3/api-docs.yaml";

  public static final String HEALTH = "/actuator/health";
  public static final String HEALTH_ALL = "/actuator/health/**";
  public static final String INFO = "/actuator/info";
}
