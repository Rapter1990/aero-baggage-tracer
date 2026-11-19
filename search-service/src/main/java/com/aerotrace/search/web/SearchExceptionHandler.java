package com.aerotrace.search.web;

import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import com.aerotrace.common.api.ApiError;
import com.aerotrace.common.error.BaseExceptionHandler;
import com.aerotrace.common.error.ErrorCode;
import com.aerotrace.search.elastic.Elastic;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * The shared error handling, plus: an Elasticsearch that cannot be reached or is overloaded is
 * {@code 503 SEARCH_UNAVAILABLE}, never a 500 and never a raw Elasticsearch message.
 */
@RestControllerAdvice
class SearchExceptionHandler extends BaseExceptionHandler {

  private static final String UNAVAILABLE = "The search index is not available. Try again later.";

  @ExceptionHandler(IOException.class)
  ResponseEntity<ApiError> handleUnreachable(Exception ex, HttpServletRequest request) {
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

  private static ResponseEntity<ApiError> unavailable(HttpServletRequest request) {
    return ResponseEntity.status(ErrorCode.SEARCH_UNAVAILABLE.httpStatus())
        .body(ApiError.of(ErrorCode.SEARCH_UNAVAILABLE, UNAVAILABLE, request.getRequestURI()));
  }
}
