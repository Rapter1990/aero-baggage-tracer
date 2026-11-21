package com.aerotrace.notification.delivery;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The only channel in this slice: in-app notifications are read through the API, so "delivery"
 * accepts the notification. It can be told to fail, so retries, exhaustion and dead letters can be
 * exercised, and it remembers the ids of the last {@value #HISTORY_LIMIT} calls for tests to check.
 * The history is bounded because this provider also runs in every deployment of the slice: a
 * long-running instance must not grow its memory with every notification. Safe to call from several
 * threads.
 */
@Component
public class MockNotificationProvider implements NotificationProvider {

  private static final Logger LOG = LoggerFactory.getLogger(MockNotificationProvider.class);

  /** The error code of a failure the mock was told to produce. */
  public static final String MOCK_FAILURE = "PROVIDER_UNAVAILABLE";

  /** How many attempted and sent ids are remembered; older ones are forgotten first. */
  public static final int HISTORY_LIMIT = 1_000;

  private static final Consumer<OutgoingNotification> NOTHING = notification -> {};

  private final Deque<UUID> attempted = new ArrayDeque<>();
  private final Deque<UUID> sent = new ArrayDeque<>();
  private final AtomicBoolean failAll = new AtomicBoolean();
  private final AtomicInteger failNext = new AtomicInteger();
  private volatile Consumer<OutgoingNotification> onSend = NOTHING;

  @Override
  public void send(OutgoingNotification notification) {
    remember(attempted, notification.id());
    onSend.accept(notification);
    if (failAll.get() || failNext.getAndUpdate(n -> Math.max(n - 1, 0)) > 0) {
      throw new DeliveryFailedException(MOCK_FAILURE);
    }
    remember(sent, notification.id());
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
    synchronized (attempted) {
      attempted.clear();
    }
    synchronized (sent) {
      sent.clear();
    }
  }

  /**
   * The ids of the last calls (at most {@value #HISTORY_LIMIT}), in call order, repeats included.
   */
  public List<UUID> attemptedIds() {
    synchronized (attempted) {
      return List.copyOf(attempted);
    }
  }

  /** The ids of the last calls that succeeded (at most {@value #HISTORY_LIMIT}), in call order. */
  public List<UUID> sentIds() {
    synchronized (sent) {
      return List.copyOf(sent);
    }
  }

  private static void remember(Deque<UUID> history, UUID id) {
    synchronized (history) {
      if (history.size() == HISTORY_LIMIT) {
        history.removeFirst();
      }
      history.addLast(id);
    }
  }
}
