package com.aerotrace.search.web;

import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import com.aerotrace.common.api.ApiError;
import com.aerotrace.common.error.BaseExceptionHandler;
import com.aerotrace.common.error.ErrorCode;
import com.aerotrace.search.elastic.Elastic;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.util.DisconnectedClientHelper;

/**
 * The shared error handling, plus: an Elasticsearch that cannot be reached or is overloaded is
 * {@code 503 SEARCH_UNAVAILABLE}, never a 500 and never a raw Elasticsearch message.
 */
@RestControllerAdvice
class SearchExceptionHandler extends BaseExceptionHandler {

  private static final String UNAVAILABLE = "The search index is not available. Try again later.";

  /**
   * Elasticsearch calls are wrapped by {@link Elastic#call}, so an {@link IOException} that gets
   * here is either the client going away (nothing can be written back, and it is not the index's
   * fault: no 503) or an unwrapped transport failure (503).
   */
  @ExceptionHandler(IOException.class)
  ResponseEntity<ApiError> handleUnreachable(IOException ex, HttpServletRequest request) {
    if (DisconnectedClientHelper.isClientDisconnectedException(ex)) {
      return null;
    }
    return unavailable(request);
  }

  @ExceptionHandler(ElasticsearchException.class)
  ResponseEntity<ApiError> handleElasticsearch(
      ElasticsearchException ex, HttpServletRequest request) {
    if (Elastic.isTransient(ex.status())) {
      return unavailable(request);
    }
    return handleUnexpected(ex, request);
  }

  /** The same for the calls made through Spring Data, which wraps the client's exceptions. */
  @ExceptionHandler(DataAccessException.class)
  ResponseEntity<ApiError> handleDataAccess(DataAccessException ex, HttpServletRequest request) {
    if (Elastic.unavailable(ex)) {
      return unavailable(request);
    }
    return handleUnexpected(ex, request);
  }

  private static ResponseEntity<ApiError> unavailable(HttpServletRequest request) {
    return ResponseEntity.status(ErrorCode.SEARCH_UNAVAILABLE.httpStatus())
        .body(ApiError.of(ErrorCode.SEARCH_UNAVAILABLE, UNAVAILABLE, request.getRequestURI()));
  }
}
