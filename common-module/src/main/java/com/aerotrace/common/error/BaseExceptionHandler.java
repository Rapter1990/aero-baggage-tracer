package com.aerotrace.common.error;

import com.aerotrace.common.api.ApiError;
import com.aerotrace.common.logging.LogMasker;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Shared handler logic: every error leaves a service as an {@link ApiError} with a stable code.
 * Servlet (Spring MVC) only.
 *
 * <p>Deliberately not annotated: {@code CommonServletAutoConfiguration} registers a default advice
 * when a service has none. A service that needs extra handlers extends this class in its own
 * {@code @RestControllerAdvice}; the default then backs off, so there is never a second advice.
 *
 * <p>Messages never carry internals or submitted values: unexpected failures get a fixed text, and
 * validation errors report the field and the rule, not what was sent.
 */
public class BaseExceptionHandler extends ResponseEntityExceptionHandler {

  private static final Logger LOG = LoggerFactory.getLogger(BaseExceptionHandler.class);

  @ExceptionHandler(ApiException.class)
  public ResponseEntity<ApiError> handleApiException(ApiException ex, HttpServletRequest request) {
    ApiError error = ApiError.of(ex.errorCode(), ex.getMessage(), request.getRequestURI());
    return ResponseEntity.status(ex.errorCode().httpStatus()).body(error);
  }

  @ExceptionHandler(AccessDeniedException.class)
  public ResponseEntity<ApiError> handleAccessDenied(
      AccessDeniedException ex, HttpServletRequest request) {
    ApiError error =
        ApiError.of(ErrorCode.ACCESS_DENIED, "Access is denied.", request.getRequestURI());
    return ResponseEntity.status(HttpStatus.FORBIDDEN).body(error);
  }

  @ExceptionHandler(AuthenticationException.class)
  public ResponseEntity<ApiError> handleAuthentication(
      AuthenticationException ex, HttpServletRequest request) {
    ApiError error =
        ApiError.of(
            ErrorCode.UNAUTHENTICATED, "Authentication is required.", request.getRequestURI());
    return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(error);
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<ApiError> handleUnexpected(Exception ex, HttpServletRequest request) {
    // Log the cause server-side; never echo internals to the client.
    // The message is masked because persistence/validation errors can embed passenger data.
    LOG.error(
        "Unhandled exception {}: {}", ex.getClass().getName(), LogMasker.mask(ex.getMessage()));
    ApiError error =
        ApiError.of(
            ErrorCode.INTERNAL_ERROR, "An unexpected error occurred.", request.getRequestURI());
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
  }

  /**
   * Bean Validation failures on a request body: one entry per field, rule only, never the value.
   */
  @Override
  protected ResponseEntity<Object> handleMethodArgumentNotValid(
      MethodArgumentNotValidException ex,
      HttpHeaders headers,
      HttpStatusCode status,
      WebRequest request) {
    Map<String, String> errors = new LinkedHashMap<>();
    for (FieldError fieldError : ex.getBindingResult().getFieldErrors()) {
      errors.putIfAbsent(fieldError.getField(), fieldError.getDefaultMessage());
    }
    ex.getBindingResult()
        .getGlobalErrors()
        .forEach(error -> errors.putIfAbsent(error.getObjectName(), error.getDefaultMessage()));
    return validationResponse(errors, headers, request);
  }

  /** Bean Validation failures on method parameters such as request parameters. */
  @Override
  protected ResponseEntity<Object> handleHandlerMethodValidationException(
      HandlerMethodValidationException ex,
      HttpHeaders headers,
      HttpStatusCode status,
      WebRequest request) {
    Map<String, String> errors = new LinkedHashMap<>();
    ex.getParameterValidationResults()
        .forEach(
            result -> {
              String name =
                  result.getMethodParameter().getParameterName() != null
                      ? result.getMethodParameter().getParameterName()
                      : "arg" + result.getMethodParameter().getParameterIndex();
              result.getResolvableErrors().stream()
                  .map(MessageSourceResolvable::getDefaultMessage)
                  .findFirst()
                  .ifPresent(message -> errors.putIfAbsent(name, message));
            });
    return validationResponse(errors, headers, request);
  }

  private ResponseEntity<Object> validationResponse(
      Map<String, String> errors, HttpHeaders headers, WebRequest request) {
    ApiError error =
        ApiError.validation(
            "Request validation failed", HttpStatus.BAD_REQUEST.value(), path(request), errors);
    return ResponseEntity.badRequest().headers(headers).body(error);
  }

  /** Spring MVC's own errors (405, 415, unreadable body, no handler...) in the shared shape. */
  @Override
  protected ResponseEntity<Object> handleExceptionInternal(
      Exception ex,
      Object body,
      HttpHeaders headers,
      HttpStatusCode statusCode,
      WebRequest request) {
    ProblemDetail framework =
        body instanceof ProblemDetail problem
            ? problem
            : ex instanceof ErrorResponse errorResponse ? errorResponse.getBody() : null;
    String message = framework != null ? framework.getDetail() : null;
    if (message == null || message.isBlank()) {
      HttpStatus known = HttpStatus.resolve(statusCode.value());
      message = known != null ? known.getReasonPhrase() : "Request failed.";
    }
    ApiError error =
        ApiError.of(
            ErrorCode.fromStatus(statusCode.value()).name(),
            message,
            statusCode.value(),
            path(request));
    return ResponseEntity.status(statusCode).headers(headers).body(error);
  }

  private static String path(WebRequest request) {
    return request instanceof ServletWebRequest servlet ? servlet.getRequest().getRequestURI() : "";
  }
}
