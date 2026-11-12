package com.aerotrace.identity.constant;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/** Names used in the OpenAPI definition. */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class DocsConstants {

  public static final String TAG_TOKENS = "Tokens";
  public static final String TAG_ACCOUNT = "Account";
  public static final String TAG_SIGNING_KEYS = "Signing keys";

  public static final String BEARER_AUTH = "bearerAuth";
  public static final String API_ERROR_SCHEMA = "ApiError";
  public static final String API_ERROR_REF = "#/components/schemas/" + API_ERROR_SCHEMA;
}
