package com.aerotrace.search.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/** An I/O failure is 503 only when it is not the client that went away. */
class SearchExceptionHandlerTest {

  private final SearchExceptionHandler handler = new SearchExceptionHandler();

  @Test
  @DisplayName("a client that disconnected gets no 503 SEARCH_UNAVAILABLE (nothing is written)")
  void clientAbortIsNotUnavailable() {
    assertThat(
            handler.handleUnreachable(
                new IOException("Broken pipe"), new MockHttpServletRequest("GET", "/x")))
        .isNull();
  }

  @Test
  @DisplayName("any other I/O failure is 503 SEARCH_UNAVAILABLE")
  void otherIoFailureIsUnavailable() {
    var response =
        handler.handleUnreachable(
            new IOException("connect timed out"), new MockHttpServletRequest("GET", "/x"));

    assertThat(response.getStatusCode().value()).isEqualTo(503);
    assertThat(response.getBody().code()).isEqualTo("SEARCH_UNAVAILABLE");
  }
}
