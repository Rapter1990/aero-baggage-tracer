package com.aerotrace.common.error;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.context.request.ServletWebRequest;

class SecurityAndFrameworkErrorsTest {

  private final BaseExceptionHandler handler = new BaseExceptionHandler();

  @Test
  void accessDeniedIs403NotInternalError() {
    var response = handler.handleAccessDenied(new AccessDeniedException("nope"));
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(response.getBody().getProperties()).containsEntry("errorCode", "ACCESS_DENIED");
  }

  @Test
  void authenticationFailureIs401() {
    var response = handler.handleAuthentication(new BadCredentialsException("bad"));
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(response.getBody().getProperties()).containsEntry("errorCode", "UNAUTHENTICATED");
  }

  @Test
  void unmappedClientStatusesAreRequestRejectedNotInternalError() {
    assertThat(ErrorCode.fromStatus(405)).isEqualTo(ErrorCode.REQUEST_REJECTED);
    assertThat(ErrorCode.fromStatus(415)).isEqualTo(ErrorCode.REQUEST_REJECTED);
    assertThat(ErrorCode.fromStatus(500)).isEqualTo(ErrorCode.INTERNAL_ERROR);
    assertThat(ErrorCode.fromStatus(409)).isEqualTo(ErrorCode.VERSION_CONFLICT);
  }

  @Test
  void springMvcMethodNotAllowedKeepsStatusAndGetsErrorCode() throws Exception {
    var request = new ServletWebRequest(new MockHttpServletRequest("DELETE", "/bags"));
    var ex = new HttpRequestMethodNotSupportedException("DELETE");

    var response = handler.handleException(ex, request);

    assertThat(response).isNotNull();
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
    ProblemDetail pd = (ProblemDetail) response.getBody();
    assertThat(pd.getStatus()).isEqualTo(405);
    assertThat(pd.getProperties()).containsEntry("errorCode", "REQUEST_REJECTED");
  }
}
