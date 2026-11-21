package com.aerotrace.notification.delivery;

/**
 * A delivery channel. It is called outside any transaction, with a hard timeout.
 *
 * <p>Delivery is at least once: a crash, a lost lease or a timeout after the channel accepted the
 * message sends it again with the same {@link OutgoingNotification#id() id}. That id ({@link
 * OutgoingNotification#idempotencyKey()}) is the idempotency key, and a real provider must honor
 * it: a second call with an id it already accepted must not reach the user twice (pass it to the
 * channel's own idempotency mechanism, or deduplicate by it). The mock provider of this slice does
 * not need to.
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
