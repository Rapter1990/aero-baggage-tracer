package com.aerotrace.search.elastic;

import com.aerotrace.common.error.ApiException;
import com.aerotrace.common.error.ErrorCode;

/** Elasticsearch did not answer, or answered that it cannot serve now: a 503 for the caller. */
public class SearchUnavailableException extends ApiException {

  public SearchUnavailableException(Throwable cause) {
    super(ErrorCode.SEARCH_UNAVAILABLE, "The search index is not available. Try again later.");
    initCause(cause);
  }
}
