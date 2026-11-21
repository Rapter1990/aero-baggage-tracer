package com.aerotrace.notification.docs;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * Example bodies shown in the OpenAPI definition (the same as docs/apis/NOTIFICATION-API.md).
 * Values such as the time are illustrative; the messages are the ones the service really sends.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class ApiExamples {

  public static final String LIST =
      """
      {"success":true,"data":{"items":[\
      {"id":"7c1d3a52-9f0e-4b7e-8a41-5d2c6e9b0f13","caseId":"0b6f9c1e-3a52-4d0e-9a59-2b8f6b1d7c11",\
      "bagTag":"0123456789","state":"RESERVED","version":4,"template":"CASE_RESERVED",\
      "status":"SENT","attempts":2,"createdAt":"2026-11-03T08:20:00Z",\
      "sentAt":"2026-11-03T08:20:07Z"},\
      {"id":"2e8b5f40-61a7-4c39-b0d2-9a7f13c84e55","caseId":"0b6f9c1e-3a52-4d0e-9a59-2b8f6b1d7c11",\
      "bagTag":"0123456789","state":"ASSESSING","version":3,"template":"CASE_ASSESSING",\
      "status":"FAILED","attempts":5,"createdAt":"2026-11-03T08:10:00Z","sentAt":null}],\
      "page":0,"size":2,"totalElements":7,"totalPages":4},"timestamp":"2026-11-03T10:00:00Z"}\
      """;

  public static final String ONE =
      """
      {"success":true,"data":{"id":"7c1d3a52-9f0e-4b7e-8a41-5d2c6e9b0f13",\
      "caseId":"0b6f9c1e-3a52-4d0e-9a59-2b8f6b1d7c11","bagTag":"0123456789","state":"RESERVED",\
      "version":4,"template":"CASE_RESERVED","status":"PENDING","attempts":1,\
      "createdAt":"2026-11-03T08:20:00Z","sentAt":null},"timestamp":"2026-11-03T10:00:00Z"}\
      """;

  public static final String VALIDATION_ERROR =
      """
      {"code":"VALIDATION_ERROR","message":"Request validation failed","status":400,\
      "path":"/api/v1/notifications","validationErrors":{"size":"size must be between 1 and 100"},\
      "timestamp":"2026-11-03T10:00:00Z","correlationId":"5f1c9e0a-7b3d-4e2a-8c64-0d9a2b7e1f38"}\
      """;

  public static final String NOT_FOUND =
      """
      {"code":"NOT_FOUND","message":"Notification not found","status":404,\
      "path":"/api/v1/notifications/7c1d3a52-9f0e-4b7e-8a41-5d2c6e9b0f13","validationErrors":{},\
      "timestamp":"2026-11-03T10:00:00Z","correlationId":"5f1c9e0a-7b3d-4e2a-8c64-0d9a2b7e1f38"}\
      """;

  public static final String UNAUTHENTICATED =
      """
      {"code":"UNAUTHENTICATED","message":"Authentication is required","status":401,\
      "path":"/api/v1/notifications","validationErrors":{},\
      "timestamp":"2026-11-03T10:00:00Z","correlationId":"5f1c9e0a-7b3d-4e2a-8c64-0d9a2b7e1f38"}\
      """;

  public static final String ACCESS_DENIED =
      """
      {"code":"ACCESS_DENIED","message":"You do not have permission to do this","status":403,\
      "path":"/api/v1/notifications","validationErrors":{},\
      "timestamp":"2026-11-03T10:00:00Z","correlationId":"5f1c9e0a-7b3d-4e2a-8c64-0d9a2b7e1f38"}\
      """;
}
