package com.aerotrace.common.error;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Shared advice: every error leaves a service as RFC 7807 {@link ProblemDetail} with a stable
 * {@code errorCode}. Services import or extend it.
 */
@RestControllerAdvice
public class BaseExceptionHandler extends ResponseEntityExceptionHandler {

  private static final Logger LOG = LoggerFactory.getLogger(BaseExceptionHandler.class);

  @ExceptionHandler(ApiException.class)
  public ResponseEntity<ProblemDetail> handleApiException(ApiException ex) {
    ProblemDetail pd = ProblemDetails.of(ex.errorCode(), ex.getMessage());
    return ResponseEntity.status(ex.errorCode().httpStatus()).body(pd);
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<ProblemDetail> handleUnexpected(Exception ex) {
    // Log the cause server-side; never echo internals to the client.
    LOG.error("Unhandled exception", ex);
    ProblemDetail pd = ProblemDetails.of(ErrorCode.INTERNAL_ERROR, "An unexpected error occurred.");
    return ResponseEntity.status(ErrorCode.INTERNAL_ERROR.httpStatus()).body(pd);
  }

  /** Enrich Spring MVC's own ProblemDetail responses with errorCode and correlationId. */
  @Override
  protected ResponseEntity<Object> handleExceptionInternal(
      Exception ex,
      Object body,
      HttpHeaders headers,
      HttpStatusCode statusCode,
      WebRequest request) {
    if (body instanceof ProblemDetail framework) {
      ProblemDetail enriched =
          ProblemDetails.of(ErrorCode.fromStatus(statusCode.value()), framework.getDetail());
      enriched.setStatus(statusCode.value());
      if (framework.getTitle() != null) {
        enriched.setTitle(framework.getTitle());
      }
      return super.handleExceptionInternal(ex, enriched, headers, statusCode, request);
    }
    return super.handleExceptionInternal(ex, body, headers, statusCode, request);
  }
}
