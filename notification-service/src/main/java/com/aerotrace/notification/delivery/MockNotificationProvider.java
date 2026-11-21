package com.aerotrace.notification.delivery;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The only channel in this slice: in-app notifications are read through the API, so "delivery"
 * accepts the notification and remembers it. It can be told to fail, so retries, exhaustion and
 * dead letters can be exercised. Safe to call from several threads.
 */
@Component
public class MockNotificationProvider implements NotificationProvider {

  private static final Logger LOG = LoggerFactory.getLogger(MockNotificationProvider.class);

  /** The error code of a failure the mock was told to produce. */
  public static final String MOCK_FAILURE = "PROVIDER_UNAVAILABLE";

  private static final Consumer<OutgoingNotification> NOTHING = notification -> {};

  private final List<UUID> attempted = new CopyOnWriteArrayList<>();
  private final List<UUID> sent = new CopyOnWriteArrayList<>();
  private final AtomicBoolean failAll = new AtomicBoolean();
  private final AtomicInteger failNext = new AtomicInteger();
  private volatile Consumer<OutgoingNotification> onSend = NOTHING;

  @Override
  public void send(OutgoingNotification notification) {
    attempted.add(notification.id());
    onSend.accept(notification);
    if (failAll.get() || failNext.getAndUpdate(n -> Math.max(n - 1, 0)) > 0) {
      throw new DeliveryFailedException(MOCK_FAILURE);
    }
    sent.add(notification.id());
    LOG.debug(
        "Delivered notification {} for case {} template {}",
        notification.id(),
        notification.caseId(),
        notification.template());
  }

  /** Every call fails until {@link #reset()}. */
  public void failAll() {
    failAll.set(true);
  }

  /** The next {@code count} calls fail. */
  public void failNext(int count) {
    failNext.set(count);
  }

  /** Runs {@code hook} inside every call, before it succeeds or fails. */
  public void onSend(Consumer<OutgoingNotification> hook) {
    onSend = hook == null ? NOTHING : hook;
  }

  /** Healthy again, with no history. */
  public void reset() {
    failAll.set(false);
    failNext.set(0);
    onSend = NOTHING;
    attempted.clear();
    sent.clear();
  }

  /** Every notification id the provider was called with, in call order (repeats included). */
  public List<UUID> attemptedIds() {
    return List.copyOf(attempted);
  }

  /** The ids of the calls that succeeded, in call order. */
  public List<UUID> sentIds() {
    return List.copyOf(sent);
  }
}
