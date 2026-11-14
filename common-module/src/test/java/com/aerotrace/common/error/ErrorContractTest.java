package com.aerotrace.common.error;

import static org.assertj.core.api.Assertions.assertThat;

import com.aerotrace.common.api.ApiError;
import java.lang.reflect.Method;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.context.request.ServletWebRequest;

class ErrorContractTest {

  private final BaseExceptionHandler handler = new BaseExceptionHandler();
  private final MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/bags");

  @SuppressWarnings("unused")
  private void endpoint(Object body) {}

  @Test
  void duplicateBagTagIsStable409() {
    // Given an ApiException for a duplicate bag tag
    var ex = new ApiException(ErrorCode.BAG_TAG_DUPLICATE, "Bag AB123456 already exists");
    MDC.put("correlationId", "corr-1");
    try {
      // When handled
      ResponseEntity<ApiError> response = handler.handleApiException(ex, request);

      // Then the contract is stable
      assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
      ApiError error = response.getBody();
      assertThat(error).isNotNull();
      assertThat(error.code()).isEqualTo("BAG_TAG_DUPLICATE");
      assertThat(error.message()).isEqualTo("Bag AB123456 already exists");
      assertThat(error.status()).isEqualTo(409);
      assertThat(error.path()).isEqualTo("/api/v1/bags");
      assertThat(error.correlationId()).isEqualTo("corr-1");
      assertThat(error.timestamp()).isNotNull();
    } finally {
      MDC.clear();
    }
  }

  @Test
  void everyErrorCodeHasAnErrorStatus() {
    for (ErrorCode code : ErrorCode.values()) {
      assertThat(code.httpStatus().value()).isBetween(400, 599);
    }
    assertThat(ErrorCode.ILLEGAL_TRANSITION.httpStatus()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(ErrorCode.CAPACITY_EXCEEDED.httpStatus()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(ErrorCode.UNAUTHENTICATED.httpStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(ErrorCode.ACCESS_DENIED.httpStatus()).isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(ErrorCode.VALIDATION_ERROR.httpStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
  }

  @Test
  void rateLimitedIsStable429AndMapsFromStatus() {
    assertThat(ErrorCode.RATE_LIMITED.httpStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    assertThat(ErrorCode.fromStatus(429)).isEqualTo(ErrorCode.RATE_LIMITED);
    assertThat(ErrorCode.fromStatus(400)).isEqualTo(ErrorCode.VALIDATION_ERROR);
    assertThat(ApiError.of(ErrorCode.RATE_LIMITED, "slow down", "/x").status()).isEqualTo(429);
    assertThat(ApiError.of(ErrorCode.RATE_LIMITED, "slow down", "/x").code())
        .isEqualTo("RATE_LIMITED");
  }

  @Test
  void missingCorrelationIdIsSimplyAbsent() {
    ApiError error = ApiError.of(ErrorCode.ILLEGAL_TRANSITION, "RESOLVED -> IN_TRANSIT", "/x");

    assertThat(error.correlationId()).isNull();
    assertThat(error.status()).isEqualTo(409);
  }

  @Test
  void unexpectedExceptionDoesNotLeakDetails() {
    ResponseEntity<ApiError> response =
        handler.handleUnexpected(new IllegalStateException("db password is hunter2"), request);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    assertThat(response.getBody().message()).doesNotContain("hunter2");
    assertThat(response.getBody().code()).isEqualTo("INTERNAL_ERROR");
  }

  @Test
  void invalidInputListsEachFieldWithItsMessage() throws Exception {
    Method method = ErrorContractTest.class.getDeclaredMethod("endpoint", Object.class);
    var binding = new BeanPropertyBindingResult(new Object(), "body");
    binding.addError(new FieldError("body", "username", "must not be blank"));
    binding.addError(new FieldError("body", "password", "size must be between 12 and 72"));
    var ex = new MethodArgumentNotValidException(new MethodParameter(method, 0), binding);

    var response =
        handler.handleMethodArgumentNotValid(
            ex, new HttpHeaders(), HttpStatus.BAD_REQUEST, new ServletWebRequest(request));

    ApiError error = (ApiError) response.getBody();
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(error.code()).isEqualTo("VALIDATION_ERROR");
    assertThat(error.validationErrors())
        .containsAllEntriesOf(
            Map.of("username", "must not be blank", "password", "size must be between 12 and 72"));
    assertThat(error.path()).isEqualTo("/api/v1/bags");
  }

  @Test
  void aRejectedValueIsNeverEchoedBack() throws Exception {
    Method method = ErrorContractTest.class.getDeclaredMethod("endpoint", Object.class);
    var binding = new BeanPropertyBindingResult(new Object(), "body");
    binding.addError(
        new FieldError("body", "password", "hunter2-secret", false, null, null, "too short"));
    var ex = new MethodArgumentNotValidException(new MethodParameter(method, 0), binding);

    var response =
        handler.handleMethodArgumentNotValid(
            ex, new HttpHeaders(), HttpStatus.BAD_REQUEST, new ServletWebRequest(request));

    assertThat(String.valueOf(response.getBody())).doesNotContain("hunter2-secret");
  }
}
