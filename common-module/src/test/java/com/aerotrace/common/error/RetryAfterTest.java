package com.aerotrace.common.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aerotrace.common.api.ApiError;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

/** A refused call can say when to come back: the 429 then carries a Retry-After header. */
class RetryAfterTest {

  private final BaseExceptionHandler handler = new BaseExceptionHandler();
  private final MockHttpServletRequest request =
      new MockHttpServletRequest("POST", "/api/v1/auth/login");

  @Test
  void aRateLimitedExceptionWithADelayAnswers429WithRetryAfterInWholeSeconds() {
    ApiException ex = ApiException.rateLimited("Too many attempts", Duration.ofMillis(61_500));

    ResponseEntity<ApiError> response = handler.handleApiException(ex, request);

    assertThat(response.getStatusCode().value()).isEqualTo(429);
    assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("62");
    assertThat(response.getBody().code()).isEqualTo("RATE_LIMITED");
    assertThat(response.getBody().message()).isEqualTo("Too many attempts");
  }

  @Test
  void theDelayIsAtLeastOneSecond() {
    ApiException ex = ApiException.rateLimited("slow down", Duration.ZERO);

    ResponseEntity<ApiError> response = handler.handleApiException(ex, request);

    assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("1");
  }

  @Test
  void anExceptionWithoutADelayHasNoRetryAfter() {
    ApiException ex = new ApiException(ErrorCode.RATE_LIMITED, "slow down");

    ResponseEntity<ApiError> response = handler.handleApiException(ex, request);

    assertThat(response.getHeaders().containsHeader(HttpHeaders.RETRY_AFTER)).isFalse();
    assertThat(ex.retryAfter()).isNull();
  }

  @Test
  void aNegativeDelayIsRefused() {
    assertThatThrownBy(() -> ApiException.rateLimited("x", Duration.ofSeconds(-1)))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
