package com.aerotrace.search.indexer;

/**
 * Elasticsearch refused part of a bulk request with a status that may pass (429, 5xx), and it still
 * did after a few quick retries. The whole batch is retried by the listener's error handler (every
 * write is idempotent); its offsets are not committed until it succeeds.
 */
public class TransientIndexingException extends RuntimeException {

  public TransientIndexingException(String message) {
    super(message);
  }
}
