package com.aerotrace.common.error;

import static org.assertj.core.api.Assertions.assertThat;

import com.aerotrace.common.api.ApiError;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.TypeMismatchException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.context.request.ServletWebRequest;

/**
 * Spring builds its own error texts from what the client sent. None of that may reach the response:
 * a value typed into a URL can be a passenger reference or an email address, and response bodies
 * end up in proxy and client logs.
 */
class NoEchoErrorsTest {

  private static final String SECRET = "P-00000000000000000001";

  private final BaseExceptionHandler handler = new BaseExceptionHandler();
  private final MockHttpServletRequest request = new MockHttpServletRequest("GET", "/bags");

  @AfterEach
  void clearSecurityContext() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void aParameterOfTheWrongTypeNamesTheParameterButNeverTheValue() throws Exception {
    var ex = new TypeMismatchException(SECRET, Integer.class);
    ex.initPropertyName("limit");

    var response =
        handler.handleTypeMismatch(
            ex, new HttpHeaders(), HttpStatus.BAD_REQUEST, new ServletWebRequest(request));

    ApiError error = (ApiError) response.getBody();
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(error.code()).isEqualTo("VALIDATION_ERROR");
    assertThat(error.validationErrors()).containsEntry("limit", "has an invalid format");
    assertThat(String.valueOf(error)).doesNotContain(SECRET);
  }

  @Test
  void anUnsupportedContentTypeIsNotQuotedBack() throws Exception {
    var ex =
        new HttpMediaTypeNotSupportedException(
            MediaType.parseMediaType("text/" + "x-" + SECRET), java.util.List.of());

    var response = handler.handleException(ex, new ServletWebRequest(request));

    ApiError error = (ApiError) response.getBody();
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
    assertThat(error.message()).isEqualTo("Unsupported media type.");
    assertThat(String.valueOf(error)).doesNotContainIgnoringCase(SECRET);
  }

  @Test
  void anUnknownPathIsNotQuotedBackEither() throws Exception {
    var ex =
        new org.springframework.web.servlet.resource.NoResourceFoundException(
            org.springframework.http.HttpMethod.GET, "/" + SECRET, SECRET);

    var response = handler.handleException(ex, new ServletWebRequest(request));

    ApiError error = (ApiError) response.getBody();
    assertThat(error.message()).isEqualTo("Resource not found.");
    assertThat(error.message()).doesNotContain(SECRET);
  }

  @Test
  void anUnacceptableTypeIsNotQuotedBack() throws Exception {
    var response =
        handler.handleException(
            new HttpMediaTypeNotAcceptableException("nope " + SECRET),
            new ServletWebRequest(request));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_ACCEPTABLE);
    assertThat(String.valueOf(response.getBody())).doesNotContain(SECRET);
  }

  @Test
  void anAnonymousCallerRefusedByMethodSecurityGetsA401NotA403() {
    SecurityContextHolder.getContext()
        .setAuthentication(
            new AnonymousAuthenticationToken(
                "key", "anonymous", AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS")));

    var response = handler.handleAccessDenied(new AccessDeniedException("no"), request);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(response.getBody().code()).isEqualTo("UNAUTHENTICATED");
  }

  @Test
  void noAuthenticationAtAllIsAlsoA401() {
    var response = handler.handleAccessDenied(new AccessDeniedException("no"), request);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
  }

  @Test
  void aSignedInCallerWithoutTheRoleStillGetsA403() {
    SecurityContextHolder.getContext()
        .setAuthentication(
            UsernamePasswordAuthenticationToken.authenticated(
                "alice", "n/a", AuthorityUtils.createAuthorityList("ROLE_PASSENGER")));

    var response = handler.handleAccessDenied(new AccessDeniedException("no"), request);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(response.getBody().code()).isEqualTo("ACCESS_DENIED");
  }
}
