package com.aerotrace.common.error;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;

class ErrorContractTest {

  private final BaseExceptionHandler handler = new BaseExceptionHandler();

  @Test
  void duplicateBagTagIsStable409() {
    // Given an ApiException for a duplicate bag tag
    var ex = new ApiException(ErrorCode.BAG_TAG_DUPLICATE, "Bag AB123456 already exists");
    MDC.put("correlationId", "corr-1");
    try {
      // When handled
      ResponseEntity<ProblemDetail> response = handler.handleApiException(ex);

      // Then the RFC 7807 contract is stable
      assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
      ProblemDetail pd = response.getBody();
      assertThat(pd).isNotNull();
      assertThat(pd.getStatus()).isEqualTo(409);
      assertThat(pd.getTitle()).isEqualTo("Bag tag already registered");
      assertThat(pd.getDetail()).isEqualTo("Bag AB123456 already exists");
      assertThat(pd.getType().toString()).isEqualTo("urn:aerotrace:problem:BAG_TAG_DUPLICATE");
      assertThat(pd.getProperties())
          .containsEntry("errorCode", "BAG_TAG_DUPLICATE")
          .containsEntry("correlationId", "corr-1")
          .containsKey("timestamp");
    } finally {
      MDC.clear();
    }
  }

  @Test
  void everyErrorCodeHasStatusAndTitle() {
    for (ErrorCode code : ErrorCode.values()) {
      assertThat(code.httpStatus().value()).isBetween(400, 599);
      assertThat(code.title()).isNotBlank();
    }
    assertThat(ErrorCode.ILLEGAL_TRANSITION.httpStatus()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(ErrorCode.CAPACITY_EXCEEDED.httpStatus()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(ErrorCode.UNAUTHENTICATED.httpStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(ErrorCode.ACCESS_DENIED.httpStatus()).isEqualTo(HttpStatus.FORBIDDEN);
  }

  @Test
  void rateLimitedIsStable429AndMapsFromStatus() {
    assertThat(ErrorCode.RATE_LIMITED.httpStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    assertThat(ErrorCode.fromStatus(429)).isEqualTo(ErrorCode.RATE_LIMITED);
    assertThat(ProblemDetails.of(ErrorCode.RATE_LIMITED, "slow down").getStatus()).isEqualTo(429);
  }

  @Test
  void missingCorrelationIdOmitsProperty() {
    ProblemDetail pd = ProblemDetails.of(ErrorCode.ILLEGAL_TRANSITION, "RESOLVED -> IN_TRANSIT");
    assertThat(pd.getProperties()).doesNotContainKey("correlationId");
    assertThat(pd.getStatus()).isEqualTo(409);
  }

  @Test
  void unexpectedExceptionDoesNotLeakDetails() {
    ResponseEntity<ProblemDetail> response =
        handler.handleUnexpected(new IllegalStateException("db password is hunter2"));
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    assertThat(response.getBody().getDetail()).doesNotContain("hunter2");
    assertThat(response.getBody().getProperties()).containsEntry("errorCode", "INTERNAL_ERROR");
  }
}
