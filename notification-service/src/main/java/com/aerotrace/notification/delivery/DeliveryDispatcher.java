package com.aerotrace.notification.delivery;

import com.aerotrace.common.correlation.CorrelationScope;
import com.aerotrace.notification.config.NotificationProperties;
import com.aerotrace.notification.constant.MetricNames;
import com.aerotrace.notification.repository.DeliveryRepository;
import com.aerotrace.notification.repository.DeliveryRepository.Claim;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Sends due notifications: claim, send, finish (ADR 0005, decision 9). No transaction or lock is
 * held while the provider is called:
 *
 * <ol>
 *   <li>a short claim moves {@code next_attempt_at} to the end of a lease, counts the attempt and
 *       raises the version; rows another replica holds are skipped;
 *   <li>the claimed rows are sent at once, each on its own virtual thread, under the provider
 *       timeout (shorter than the lease);
 *   <li>a guarded finish records the result only if the row still has the claimed version: SENT, a
 *       retry after a doubling wait, or FAILED after the last attempt.
 * </ol>
 *
 * Every time (lease end, due time, sent and failed time) is the database's {@code now()}. A retry
 * updates the same row; it never inserts one. A crash between the send and the finish sends again
 * after the lease: delivery is at least once, keyed by the notification id. Exhausted rows are
 * dead-lettered by the separate {@link DeadLetterPublisher}, so a Kafka outage never holds up
 * sending.
 */
@Component
public class DeliveryDispatcher {

  private static final Logger LOG = LoggerFactory.getLogger(DeliveryDispatcher.class);

  static final String TIMEOUT = "TIMEOUT";

  private final DeliveryRepository deliveries;
  private final NotificationProvider provider;
  private final DeadLetterPublisher deadLetters;
  private final BackoffPolicy backoff;
  private final NotificationProperties.Delivery settings;
  private final MeterRegistry meters;
  private final ExecutorService senders = Executors.newVirtualThreadPerTaskExecutor();

  public DeliveryDispatcher(
      DeliveryRepository deliveries,
      NotificationProvider provider,
      DeadLetterPublisher deadLetters,
      BackoffPolicy backoff,
      NotificationProperties properties,
      MeterRegistry meters) {
    this.deliveries = deliveries;
    this.provider = provider;
    this.deadLetters = deadLetters;
    this.backoff = backoff;
    this.settings = properties.delivery();
    this.meters = meters;
  }

  @Scheduled(
      initialDelayString = "${aerotrace.notification.delivery.dispatch-delay:1s}",
      fixedDelayString = "${aerotrace.notification.delivery.dispatch-delay:1s}")
  void scheduledSend() {
    try {
      sendDue();
    } catch (RuntimeException e) {
      // The next run tries again; the claimed rows become due again when their lease ends.
      LOG.warn("Delivery run failed: {}", e.getClass().getSimpleName());
    }
  }

  /**
   * One full run, as the schedulers make it: send what is due, then publish the dead letters of
   * what is exhausted. A failing dead-letter publish never fails the run.
   *
   * @return how many notifications were attempted
   */
  public int dispatchDue() {
    int attempted = sendDue();
    try {
      deadLetters.publishDue();
    } catch (RuntimeException e) {
      LOG.warn("Dead-letter run failed: {}", e.getClass().getSimpleName());
    }
    return attempted;
  }

  /**
   * Claims and sends the due notifications.
   *
   * @return how many were attempted
   */
  public int sendDue() {
    int expired = deliveries.expireLeases(settings.maxAttempts());
    if (expired > 0) {
      LOG.warn("{} notifications failed: their lease expired after the last attempt", expired);
      meters
          .counter(MetricNames.DELIVERY, MetricNames.OUTCOME, MetricNames.FAILED)
          .increment(expired);
    }
    List<Claim> claims =
        deliveries.claimDue(settings.lease(), settings.maxAttempts(), settings.batchSize());
    if (claims.isEmpty()) {
      return 0;
    }
    List<Future<?>> sends = new ArrayList<>(claims.size());
    for (Claim claim : claims) {
      sends.add(senders.submit(() -> send(claim)));
    }
    long deadline = System.nanoTime() + settings.providerTimeout().toNanos();
    for (int i = 0; i < claims.size(); i++) {
      finish(claims.get(i), outcome(sends.get(i), deadline));
    }
    return claims.size();
  }

  private void send(Claim claim) {
    try (CorrelationScope ignored = CorrelationScope.of(claim.correlationId())) {
      provider.send(
          new OutgoingNotification(
              claim.id(),
              claim.recipient(),
              claim.template(),
              claim.caseId(),
              claim.bagTag(),
              claim.state(),
              claim.caseVersion()));
    }
  }

  /** Null when the send worked, otherwise the error code to record. */
  private static String outcome(Future<?> send, long deadline) {
    try {
      send.get(Math.max(deadline - System.nanoTime(), 0), TimeUnit.NANOSECONDS);
      return null;
    } catch (TimeoutException e) {
      send.cancel(true);
      return TIMEOUT;
    } catch (ExecutionException e) {
      Throwable cause = e.getCause() == null ? e : e.getCause();
      return cause instanceof DeliveryFailedException failed
          ? failed.errorCode()
          : cause.getClass().getSimpleName();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      send.cancel(true);
      return "INTERRUPTED";
    }
  }

  private void finish(Claim claim, String error) {
    try (CorrelationScope ignored = CorrelationScope.of(claim.correlationId())) {
      int updated;
      String outcome;
      if (error == null) {
        updated = deliveries.markSent(claim.id(), claim.version());
        outcome = MetricNames.SENT;
      } else if (claim.attempts() >= settings.maxAttempts()) {
        updated = deliveries.markFailed(claim.id(), claim.version(), error);
        outcome = MetricNames.FAILED;
      } else {
        updated =
            deliveries.markRetry(
                claim.id(), claim.version(), backoff.delayAfter(claim.attempts()), error);
        outcome = MetricNames.RETRY;
      }
      if (updated == 0) {
        // Another replica took the row over after this lease expired: its result counts, not ours.
        LOG.info("Delivery result of notification {} dropped: its lease was lost", claim.id());
        return;
      }
      meters.counter(MetricNames.DELIVERY, MetricNames.OUTCOME, outcome).increment();
      if (MetricNames.FAILED.equals(outcome)) {
        LOG.warn(
            "Notification {} template {} failed after {} attempts: {}",
            claim.id(),
            claim.template(),
            claim.attempts(),
            error);
      } else if (MetricNames.RETRY.equals(outcome)) {
        LOG.info(
            "Notification {} template {} attempt {} failed: {}",
            claim.id(),
            claim.template(),
            claim.attempts(),
            error);
      } else {
        LOG.debug("Notification {} template {} sent", claim.id(), claim.template());
      }
    }
  }

  @PreDestroy
  void stop() {
    senders.shutdownNow();
  }
}
