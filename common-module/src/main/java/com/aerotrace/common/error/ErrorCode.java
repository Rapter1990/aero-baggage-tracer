package com.aerotrace.common.error;

import org.springframework.http.HttpStatus;

/** Stable, machine-readable error codes shared by all services. Never rename a released code. */
public enum ErrorCode {
  VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "Request validation failed"),
  REQUEST_REJECTED(HttpStatus.BAD_REQUEST, "Request rejected"),
  UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "Authentication required"),
  ACCESS_DENIED(HttpStatus.FORBIDDEN, "Access denied"),
  NOT_FOUND(HttpStatus.NOT_FOUND, "Resource not found"),
  BAG_TAG_DUPLICATE(HttpStatus.CONFLICT, "Bag tag already registered"),
  ILLEGAL_TRANSITION(HttpStatus.CONFLICT, "Illegal state transition"),
  VERSION_CONFLICT(HttpStatus.CONFLICT, "Version conflict"),
  DUPLICATE_RECOVERY_CASE(HttpStatus.CONFLICT, "Active recovery case already exists"),
  CAPACITY_EXCEEDED(HttpStatus.CONFLICT, "Capacity exceeded"),
  INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "Internal error");

  private final HttpStatus httpStatus;
  private final String title;

  ErrorCode(HttpStatus httpStatus, String title) {
    this.httpStatus = httpStatus;
    this.title = title;
  }

  public HttpStatus httpStatus() {
    return httpStatus;
  }

  public String title() {
    return title;
  }

  /** Best-effort mapping for framework-generated errors that carry only an HTTP status. */
  public static ErrorCode fromStatus(int status) {
    return switch (status) {
      case 400 -> VALIDATION_FAILED;
      case 401 -> UNAUTHENTICATED;
      case 403 -> ACCESS_DENIED;
      case 404 -> NOT_FOUND;
      case 409 -> VERSION_CONFLICT;
      default -> status >= 400 && status < 500 ? REQUEST_REJECTED : INTERNAL_ERROR;
    };
  }
}
