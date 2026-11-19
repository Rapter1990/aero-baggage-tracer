package com.aerotrace.search.indexer;

/**
 * An event that can never be indexed, however often it is retried (not JSON, wrong type, a field
 * that breaks the contract or the strict mapping). It goes to the dead-letter topic. The message
 * names the rule that was broken and never quotes the event.
 */
public class PoisonEventException extends RuntimeException {

  public PoisonEventException(String reason) {
    super(reason);
  }

  public PoisonEventException(String reason, Throwable cause) {
    super(reason, cause);
  }
}
