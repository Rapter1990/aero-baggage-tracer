package com.aerotrace.notification.consumer;

/**
 * An event that can never be handled, however often it is retried (not JSON, wrong type, a field
 * that breaks the contract). It goes straight to the dead-letter topic. The message names the rule
 * that was broken and never quotes the event.
 */
public class PoisonEventException extends RuntimeException {

  public PoisonEventException(String reason) {
    super(reason);
  }

  public PoisonEventException(String reason, Throwable cause) {
    super(reason, cause);
  }
}
