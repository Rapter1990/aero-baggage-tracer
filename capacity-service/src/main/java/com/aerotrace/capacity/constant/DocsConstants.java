package com.aerotrace.capacity.constant;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/** Names used in the OpenAPI definition. */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class DocsConstants {

  public static final String TAG_RESERVATIONS = "Capacity reservations";
  public static final String TAG_RESOURCES = "Capacity resources";

  public static final String BEARER_AUTH = "bearerAuth";
  public static final String API_ERROR_SCHEMA = "ApiError";
  public static final String API_ERROR_REF = "#/components/schemas/" + API_ERROR_SCHEMA;
}
