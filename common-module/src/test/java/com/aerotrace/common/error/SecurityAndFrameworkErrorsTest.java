package com.aerotrace.common.error;

import static org.assertj.core.api.Assertions.assertThat;

import com.aerotrace.common.api.ApiError;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.context.request.ServletWebRequest;

class SecurityAndFrameworkErrorsTest {

  private final BaseExceptionHandler handler = new BaseExceptionHandler();
  private final MockHttpServletRequest request = new MockHttpServletRequest("DELETE", "/bags");

  @AfterEach
  void clearSecurityContext() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void accessDeniedIs403NotInternalError() {
    SecurityContextHolder.getContext()
        .setAuthentication(
            UsernamePasswordAuthenticationToken.authenticated(
                "alice", "n/a", AuthorityUtils.createAuthorityList("ROLE_PASSENGER")));

    var response = handler.handleAccessDenied(new AccessDeniedException("nope"), request);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(response.getBody().code()).isEqualTo("ACCESS_DENIED");
    assertThat(response.getBody().message()).doesNotContain("nope");
  }

  @Test
  void authenticationFailureIs401() {
    var response = handler.handleAuthentication(new BadCredentialsException("bad"), request);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(response.getBody().code()).isEqualTo("UNAUTHENTICATED");
    assertThat(response.getBody().message()).doesNotContain("bad");
  }

  @Test
  void unmappedClientStatusesAreRequestRejectedNotInternalError() {
    assertThat(ErrorCode.fromStatus(405)).isEqualTo(ErrorCode.REQUEST_REJECTED);
    assertThat(ErrorCode.fromStatus(415)).isEqualTo(ErrorCode.REQUEST_REJECTED);
    assertThat(ErrorCode.fromStatus(500)).isEqualTo(ErrorCode.INTERNAL_ERROR);
    assertThat(ErrorCode.fromStatus(409)).isEqualTo(ErrorCode.VERSION_CONFLICT);
  }

  @Test
  void springMvcMethodNotAllowedKeepsStatusAndGetsErrorCodeAndPath() throws Exception {
    var ex = new HttpRequestMethodNotSupportedException("DELETE");

    var response = handler.handleException(ex, new ServletWebRequest(request));

    assertThat(response).isNotNull();
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
    ApiError error = (ApiError) response.getBody();
    assertThat(error.status()).isEqualTo(405);
    assertThat(error.code()).isEqualTo("REQUEST_REJECTED");
    assertThat(error.path()).isEqualTo("/bags");
    assertThat(error.message()).isEqualTo("Method not allowed.");
  }
}
