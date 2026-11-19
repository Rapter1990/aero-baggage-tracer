package com.aerotrace.capacity.docs;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * Example error bodies shown in the OpenAPI definition. Values such as the time are illustrative;
 * the messages are the ones the service really sends.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class ApiExamples {

  public static final String UNAUTHENTICATED =
      """
      {"code":"UNAUTHENTICATED","message":"Authentication is required","status":401,\
      "path":"/api/v1/capacity/reservations","validationErrors":{},\
      "timestamp":"2026-11-14T10:15:30.123Z","correlationId":"b6c1d1e0-3a4f-4f0e-9d2a-5d7f0c2f8a11"}\
      """;

  public static final String ACCESS_DENIED =
      """
      {"code":"ACCESS_DENIED","message":"You do not have permission to do this","status":403,\
      "path":"/api/v1/capacity/reservations","validationErrors":{},\
      "timestamp":"2026-11-14T10:15:30.123Z","correlationId":"b6c1d1e0-3a4f-4f0e-9d2a-5d7f0c2f8a11"}\
      """;

  /** The 409 contract the recovery-service relies on: no slots are left for the leg. */
  public static final String CAPACITY_EXCEEDED =
      """
      {"code":"CAPACITY_EXCEEDED","message":"No capacity is left for this leg","status":409,\
      "path":"/api/v1/capacity/reservations","validationErrors":{},\
      "timestamp":"2026-11-14T10:15:30.123Z","correlationId":"b6c1d1e0-3a4f-4f0e-9d2a-5d7f0c2f8a11"}\
      """;

  public static final String CAPACITY_NOT_PROVISIONED =
      """
      {"code":"CAPACITY_NOT_PROVISIONED","message":"This leg has no capacity resource",\
      "status":404,"path":"/api/v1/capacity/reservations","validationErrors":{},\
      "timestamp":"2026-11-14T10:15:30.123Z","correlationId":"b6c1d1e0-3a4f-4f0e-9d2a-5d7f0c2f8a11"}\
      """;

  public static final String RESERVATION_ID_REUSED =
      """
      {"code":"VERSION_CONFLICT","message":"This reservation id was used for a different request",\
      "status":409,"path":"/api/v1/capacity/reservations","validationErrors":{},\
      "timestamp":"2026-11-14T10:15:30.123Z","correlationId":"b6c1d1e0-3a4f-4f0e-9d2a-5d7f0c2f8a11"}\
      """;

  public static final String VALIDATION_ERROR =
      """
      {"code":"VALIDATION_ERROR","message":"The request is not valid","status":400,\
      "path":"/api/v1/capacity/reservations","validationErrors":{"slots":"must be greater than or equal to 1"},\
      "timestamp":"2026-11-14T10:15:30.123Z","correlationId":"b6c1d1e0-3a4f-4f0e-9d2a-5d7f0c2f8a11"}\
      """;

  public static final String RESERVATION_NOT_FOUND =
      """
      {"code":"NOT_FOUND","message":"No such reservation","status":404,\
      "path":"/api/v1/capacity/reservations/0b6e1c52-6f4d-4d43-9d2c-1a1f0a7c9e10",\
      "validationErrors":{},"timestamp":"2026-11-14T10:15:30.123Z",\
      "correlationId":"b6c1d1e0-3a4f-4f0e-9d2a-5d7f0c2f8a11"}\
      """;

  public static final String RESOURCE_NOT_FOUND =
      """
      {"code":"NOT_FOUND","message":"No such capacity resource","status":404,\
      "path":"/api/v1/capacity/resources/LEG-2","validationErrors":{},\
      "timestamp":"2026-11-14T10:15:30.123Z","correlationId":"b6c1d1e0-3a4f-4f0e-9d2a-5d7f0c2f8a11"}\
      """;
}
