package com.aerotrace.common.error;

import org.springframework.http.HttpStatus;

/** Stable, machine-readable error codes shared by all services. Never rename a released code. */
public enum ErrorCode {
  VALIDATION_ERROR(HttpStatus.BAD_REQUEST),
  REQUEST_REJECTED(HttpStatus.BAD_REQUEST),
  UNAUTHENTICATED(HttpStatus.UNAUTHORIZED),
  ACCESS_DENIED(HttpStatus.FORBIDDEN),
  NOT_FOUND(HttpStatus.NOT_FOUND),
  BAG_TAG_DUPLICATE(HttpStatus.CONFLICT),
  USER_ALREADY_EXISTS(HttpStatus.CONFLICT),
  ILLEGAL_TRANSITION(HttpStatus.CONFLICT),
  VERSION_CONFLICT(HttpStatus.CONFLICT),
  DUPLICATE_RECOVERY_CASE(HttpStatus.CONFLICT),
  CAPACITY_EXCEEDED(HttpStatus.CONFLICT),
  /** The leg has no capacity resource (a set-up problem), as opposed to a full one. */
  CAPACITY_NOT_PROVISIONED(HttpStatus.NOT_FOUND),
  /** A dependency (for example the capacity-service) did not answer in time; retry later. */
  CAPACITY_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE),
  /** The search index (Elasticsearch) cannot be reached; the owning services are unaffected. */
  SEARCH_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE),
  /** A rebuild of the search indexes is already running; only one runs at a time. */
  REINDEX_IN_PROGRESS(HttpStatus.CONFLICT),
  /** A paging cursor whose point-in-time has expired; start the search again. */
  CURSOR_EXPIRED(HttpStatus.GONE),
  RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS),
  INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR);

  private final HttpStatus httpStatus;

  ErrorCode(HttpStatus httpStatus) {
    this.httpStatus = httpStatus;
  }

  public HttpStatus httpStatus() {
    return httpStatus;
  }

  /** Best-effort mapping for framework-generated errors that carry only an HTTP status. */
  public static ErrorCode fromStatus(int status) {
    return switch (status) {
      case 400 -> VALIDATION_ERROR;
      case 401 -> UNAUTHENTICATED;
      case 403 -> ACCESS_DENIED;
      case 404 -> NOT_FOUND;
      case 409 -> VERSION_CONFLICT;
      case 429 -> RATE_LIMITED;
      default -> status >= 400 && status < 500 ? REQUEST_REJECTED : INTERNAL_ERROR;
    };
  }
}
