package com.aerotrace.notification.delivery;

/**
 * A delivery channel. It is called outside any transaction, with a hard timeout, and must honor
 * {@link OutgoingNotification#idempotencyKey()}: delivery is at least once.
 */
public interface NotificationProvider {

  /**
   * Delivers one notification; returns normally once the channel accepted it.
   *
   * @throws DeliveryFailedException (or any other exception) when it was not accepted; the attempt
   *     is retried later
   */
  void send(OutgoingNotification notification);
}
