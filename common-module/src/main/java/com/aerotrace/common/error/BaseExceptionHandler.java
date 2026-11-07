package com.aerotrace.common.error;

import com.aerotrace.common.logging.LogMasker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.ErrorResponse;
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

  @ExceptionHandler(AccessDeniedException.class)
  public ResponseEntity<ProblemDetail> handleAccessDenied(AccessDeniedException ex) {
    ProblemDetail pd = ProblemDetails.of(ErrorCode.ACCESS_DENIED, "Access is denied.");
    return ResponseEntity.status(ErrorCode.ACCESS_DENIED.httpStatus()).body(pd);
  }

  @ExceptionHandler(AuthenticationException.class)
  public ResponseEntity<ProblemDetail> handleAuthentication(AuthenticationException ex) {
    ProblemDetail pd = ProblemDetails.of(ErrorCode.UNAUTHENTICATED, "Authentication is required.");
    return ResponseEntity.status(ErrorCode.UNAUTHENTICATED.httpStatus()).body(pd);
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<ProblemDetail> handleUnexpected(Exception ex) {
    // Log the cause server-side; never echo internals to the client.
    // The message is masked because persistence/validation errors can embed passenger data.
    LOG.error(
        "Unhandled exception {}: {}", ex.getClass().getName(), LogMasker.mask(ex.getMessage()));
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
    // Spring passes a null body for its own ErrorResponse exceptions and builds the ProblemDetail
    // itself afterwards, so take it from the exception here to enrich it.
    Object effective = body == null && ex instanceof ErrorResponse er ? er.getBody() : body;
    if (effective instanceof ProblemDetail framework) {
      ProblemDetail enriched =
          ProblemDetails.of(ErrorCode.fromStatus(statusCode.value()), framework.getDetail());
      enriched.setStatus(statusCode.value());
      if (framework.getTitle() != null) {
        enriched.setTitle(framework.getTitle());
      }
      enriched.setInstance(framework.getInstance());
      if (framework.getProperties() != null) {
        framework.getProperties().forEach(enriched::setProperty);
      }
      return super.handleExceptionInternal(ex, enriched, headers, statusCode, request);
    }
    return super.handleExceptionInternal(ex, body, headers, statusCode, request);
  }
}
