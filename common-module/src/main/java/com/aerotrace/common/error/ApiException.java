package com.aerotrace.common.error;

import java.util.Objects;

/** Business exception translated to an RFC 7807 response by {@link BaseExceptionHandler}. */
public class ApiException extends RuntimeException {

  private final ErrorCode errorCode;

  public ApiException(ErrorCode errorCode, String detail) {
    super(detail);
    this.errorCode = Objects.requireNonNull(errorCode, "errorCode");
  }

  public ErrorCode errorCode() {
    return errorCode;
  }
}
