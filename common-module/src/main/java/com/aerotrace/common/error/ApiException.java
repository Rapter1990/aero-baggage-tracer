package com.aerotrace.common.error;

import java.time.Duration;
import java.util.Objects;

/** Business exception translated to an RFC 7807 response by {@link BaseExceptionHandler}. */
public class ApiException extends RuntimeException {

  private final ErrorCode errorCode;
  private final Duration retryAfter;

  public ApiException(ErrorCode errorCode, String detail) {
    this(errorCode, detail, null);
  }

  private ApiException(ErrorCode errorCode, String detail, Duration retryAfter) {
    super(detail);
    this.errorCode = Objects.requireNonNull(errorCode, "errorCode");
    this.retryAfter = retryAfter;
  }

  /** A refused call (429) that tells the client how long to wait: sent as {@code Retry-After}. */
  public static ApiException rateLimited(String detail, Duration retryAfter) {
    Objects.requireNonNull(retryAfter, "retryAfter");
    if (retryAfter.isNegative()) {
      throw new IllegalArgumentException("retryAfter must not be negative");
    }
    return new ApiException(ErrorCode.RATE_LIMITED, detail, retryAfter);
  }

  public ErrorCode errorCode() {
    return errorCode;
  }

  /** How long the client should wait before trying again, or null when that is not known. */
  public Duration retryAfter() {
    return retryAfter;
  }
}
