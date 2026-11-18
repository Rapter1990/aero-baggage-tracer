package com.aerotrace.recovery.docs;

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
      "path":"/api/v1/recovery-cases","validationErrors":{},\
      "timestamp":"2026-11-14T10:15:30.123Z","correlationId":"b6c1d1e0-3a4f-4f0e-9d2a-5d7f0c2f8a11"}\
      """;

  public static final String ACCESS_DENIED =
      """
      {"code":"ACCESS_DENIED","message":"You do not have permission to do this","status":403,\
      "path":"/api/v1/recovery-cases","validationErrors":{},\
      "timestamp":"2026-11-14T10:15:30.123Z","correlationId":"b6c1d1e0-3a4f-4f0e-9d2a-5d7f0c2f8a11"}\
      """;

  public static final String DUPLICATE_RECOVERY_CASE =
      """
      {"code":"DUPLICATE_RECOVERY_CASE",\
      "message":"An active recovery case already exists for this bag and leg","status":409,\
      "path":"/api/v1/recovery-cases","validationErrors":{},\
      "timestamp":"2026-11-14T10:15:30.123Z","correlationId":"b6c1d1e0-3a4f-4f0e-9d2a-5d7f0c2f8a11"}\
      """;

  public static final String ILLEGAL_TRANSITION =
      """
      {"code":"ILLEGAL_TRANSITION","message":"The case is closed; a closed case cannot be reopened",\
      "status":409,"path":"/api/v1/recovery-cases/0b6e1c52-6f4d-4d43-9d2c-1a1f0a7c9e10/transitions",\
      "validationErrors":{},"timestamp":"2026-11-14T10:15:30.123Z",\
      "correlationId":"b6c1d1e0-3a4f-4f0e-9d2a-5d7f0c2f8a11"}\
      """;

  public static final String VERSION_CONFLICT =
      """
      {"code":"VERSION_CONFLICT",\
      "message":"The case was changed by someone else; reload it and try again","status":409,\
      "path":"/api/v1/recovery-cases/0b6e1c52-6f4d-4d43-9d2c-1a1f0a7c9e10/transitions",\
      "validationErrors":{},"timestamp":"2026-11-14T10:15:30.123Z",\
      "correlationId":"b6c1d1e0-3a4f-4f0e-9d2a-5d7f0c2f8a11"}\
      """;

  public static final String CAPACITY_EXCEEDED =
      """
      {"code":"CAPACITY_EXCEEDED","message":"No recovery capacity is left","status":409,\
      "path":"/api/v1/recovery-cases/0b6e1c52-6f4d-4d43-9d2c-1a1f0a7c9e10/transitions",\
      "validationErrors":{},"timestamp":"2026-11-14T10:15:30.123Z",\
      "correlationId":"b6c1d1e0-3a4f-4f0e-9d2a-5d7f0c2f8a11"}\
      """;

  public static final String CAPACITY_UNAVAILABLE =
      """
      {"code":"CAPACITY_UNAVAILABLE",\
      "message":"Recovery capacity could not be checked right now; try again shortly","status":503,\
      "path":"/api/v1/recovery-cases/0b6e1c52-6f4d-4d43-9d2c-1a1f0a7c9e10/transitions",\
      "validationErrors":{},"timestamp":"2026-11-14T10:15:30.123Z",\
      "correlationId":"b6c1d1e0-3a4f-4f0e-9d2a-5d7f0c2f8a11"}\
      """;

  public static final String CASE_NOT_FOUND =
      """
      {"code":"NOT_FOUND","message":"No such recovery case","status":404,\
      "path":"/api/v1/recovery-cases/0b6e1c52-6f4d-4d43-9d2c-1a1f0a7c9e10/transitions",\
      "validationErrors":{},"timestamp":"2026-11-14T10:15:30.123Z",\
      "correlationId":"b6c1d1e0-3a4f-4f0e-9d2a-5d7f0c2f8a11"}\
      """;

  public static final String VALIDATION_ERROR =
      """
      {"code":"VALIDATION_ERROR","message":"Validation failed","status":400,\
      "path":"/api/v1/recovery-cases",\
      "validationErrors":{"bagTag":"bagTag must be a 10-digit bag tag"},\
      "timestamp":"2026-11-14T10:15:30.123Z","correlationId":"b6c1d1e0-3a4f-4f0e-9d2a-5d7f0c2f8a11"}\
      """;

  public static final String POLICY_VERSION_CONFLICT =
      """
      {"code":"VERSION_CONFLICT",\
      "message":"The policy was changed by someone else; reload it and try again","status":409,\
      "path":"/api/v1/recovery-policy","validationErrors":{},\
      "timestamp":"2026-11-14T10:15:30.123Z","correlationId":"b6c1d1e0-3a4f-4f0e-9d2a-5d7f0c2f8a11"}\
      """;
}
