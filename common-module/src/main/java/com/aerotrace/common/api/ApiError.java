package com.aerotrace.common.api;

import com.aerotrace.common.correlation.CorrelationId;
import com.aerotrace.common.error.ErrorCode;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.Map;
import org.slf4j.MDC;

/**
 * Represents a standardized API error response, the one error shape every service returns.
 *
 * <p>Clients branch on {@code code}, a stable machine-readable value, and show {@code message}.
 * {@code correlationId} ties the response to the log lines and traces of the request; it is left
 * out when the request had none.
 *
 * @param code application-specific error code, see {@link ErrorCode}
 * @param message human-readable error message; never contains internals or submitted values
 * @param status HTTP status code
 * @param path request path where the error occurred
 * @param validationErrors field-level validation messages, empty when not a validation failure
 * @param timestamp time when the error response was created
 * @param correlationId correlation id of the request, when there is one
 */
public record ApiError(
    String code,
    String message,
    int status,
    String path,
    Map<String, String> validationErrors,
    Instant timestamp,
    @JsonInclude(JsonInclude.Include.NON_NULL) String correlationId) {

  /**
   * Creates a standard API error response without validation details.
   *
   * @param code application-specific error code
   * @param message human-readable error message
   * @param status HTTP status code
   * @param path request path where the error occurred
   * @return a new {@link ApiError} instance
   */
  public static ApiError of(String code, String message, int status, String path) {
    return new ApiError(
        code, message, status, path, Map.of(), Instant.now(), MDC.get(CorrelationId.MDC_KEY));
  }

  /**
   * Creates an error response from a shared {@link ErrorCode}, taking the status from it.
   *
   * @param code the shared error code
   * @param message human-readable error message
   * @param path request path where the error occurred
   * @return a new {@link ApiError} instance
   */
  public static ApiError of(ErrorCode code, String message, String path) {
    return of(code.name(), message, code.httpStatus().value(), path);
  }

  /**
   * Creates a validation error response with field-level validation details.
   *
   * <p>The error code is fixed as {@code VALIDATION_ERROR} because this factory method is
   * specifically used for request validation failures.
   *
   * @param message human-readable validation error message
   * @param status HTTP status code
   * @param path request path where the validation error occurred
   * @param validationErrors field-level validation error messages
   * @return a new {@link ApiError} instance for validation failures
   */
  public static ApiError validation(
      String message, int status, String path, Map<String, String> validationErrors) {
    return new ApiError(
        ErrorCode.VALIDATION_ERROR.name(),
        message,
        status,
        path,
        validationErrors,
        Instant.now(),
        MDC.get(CorrelationId.MDC_KEY));
  }
}
