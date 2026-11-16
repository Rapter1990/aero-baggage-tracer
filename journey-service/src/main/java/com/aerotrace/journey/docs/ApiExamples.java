package com.aerotrace.journey.docs;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * Example error bodies shown in the OpenAPI definition. Values such as the time are illustrative.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class ApiExamples {

  public static final String UNAUTHENTICATED =
      """
      {"code":"UNAUTHENTICATED","message":"Authentication required","status":401,\
      "path":"/api/v1/bags","validationErrors":{},\
      "timestamp":"2026-11-14T10:15:30.123Z","correlationId":"b6c1d1e0-3a4f-4f0e-9d2a-5d7f0c2f8a11"}\
      """;

  public static final String ACCESS_DENIED =
      """
      {"code":"ACCESS_DENIED","message":"Insufficient permissions","status":403,\
      "path":"/api/v1/bags","validationErrors":{},\
      "timestamp":"2026-11-14T10:15:30.123Z","correlationId":"b6c1d1e0-3a4f-4f0e-9d2a-5d7f0c2f8a11"}\
      """;

  public static final String BAG_TAG_DUPLICATE =
      """
      {"code":"BAG_TAG_DUPLICATE","message":"That bag tag is already registered","status":409,\
      "path":"/api/v1/bags","validationErrors":{},\
      "timestamp":"2026-11-14T10:15:30.123Z","correlationId":"b6c1d1e0-3a4f-4f0e-9d2a-5d7f0c2f8a11"}\
      """;

  public static final String BAG_NOT_FOUND =
      """
      {"code":"NOT_FOUND","message":"Bag not found","status":404,\
      "path":"/api/v1/bags/0123456789/journey","validationErrors":{},\
      "timestamp":"2026-11-14T10:15:30.123Z","correlationId":"b6c1d1e0-3a4f-4f0e-9d2a-5d7f0c2f8a11"}\
      """;
}
