package com.aerotrace.notification.delivery;

/**
 * A provider refused a notification. The message is an error code (for example {@code
 * PROVIDER_UNAVAILABLE}) and is stored as {@code last_error}: never a provider's free text.
 */
public class DeliveryFailedException extends RuntimeException {

  public DeliveryFailedException(String errorCode) {
    super(errorCode);
  }

  public String errorCode() {
    return getMessage();
  }
}
