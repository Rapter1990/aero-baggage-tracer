package com.aerotrace.notification.delivery;

import com.aerotrace.common.correlation.CorrelationScope;
import com.aerotrace.common.event.DeadLetterPayload;
import com.aerotrace.common.event.EventEnvelope;
import com.aerotrace.common.event.EventJson;
import com.aerotrace.common.event.Topics;
import com.aerotrace.notification.config.NotificationProperties;
import com.aerotrace.notification.constant.NotificationRules;
import com.aerotrace.notification.repository.DeliveryRepository;
import com.aerotrace.notification.repository.DeliveryRepository.DeadLetterClaim;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Publishes one dead letter per exhausted notification to {@code baggage.dead-letter.v1} (ADR 0005,
 * decision 10), separately from sending, so a Kafka outage never holds up PENDING rows. Claim,
 * send, finish as in the dispatcher: a short claim takes a lease on FAILED rows without {@code
 * dead_lettered_at}, the Kafka send runs outside any transaction, and {@code dead_lettered_at} is
 * set only after Kafka acknowledged. At least once: a consumer of the topic deduplicates by {@code
 * originalEventId}. Duplicates come from a failed publish that reached the broker anyway, and from
 * a database error after Kafka's acknowledgement: {@code dead_lettered_at} is then not set, the
 * lease ends and the same dead letter is published again.
 *
 * <p>The lease and {@code dead_lettered_at} use the database's {@code now()}; the dead letter's
 * {@code occurredAt} is the stored {@code failed_at}. Only the envelope's {@code publishedAt} comes
 * from the application clock.
 *
 * <p>The dead letter says what failed, never what the notification said or whom it was for: {@code
 * rawPayload} is null.
 */
@Component
public class DeadLetterPublisher {

  private static final Logger LOG = LoggerFactory.getLogger(DeadLetterPublisher.class);

  private final DeliveryRepository deliveries;
  private final KafkaTemplate<String, String> kafka;
  private final NotificationProperties.Delivery settings;
  private final Clock clock;

  public DeadLetterPublisher(
      DeliveryRepository deliveries,
      KafkaTemplate<String, String> kafka,
      NotificationProperties properties,
      Clock clock) {
    this.deliveries = deliveries;
    this.kafka = kafka;
    this.settings = properties.delivery();
    this.clock = clock;
  }

  @Scheduled(
      initialDelayString = "${aerotrace.notification.delivery.dispatch-delay:1s}",
      fixedDelayString = "${aerotrace.notification.delivery.dispatch-delay:1s}")
  void scheduledPublish() {
    try {
      publishDue();
    } catch (RuntimeException e) {
      LOG.warn("Dead-letter run failed: {}", e.getClass().getSimpleName());
    }
  }

  /**
   * Publishes the due dead letters. The first one Kafka does not take ends the run: the broker is
   * likely down, and the claimed rows are due again when their lease ends.
   *
   * @return how many Kafka acknowledged
   */
  public int publishDue() {
    List<DeadLetterClaim> claims =
        deliveries.claimDeadLetters(settings.lease(), settings.batchSize());
    int published = 0;
    for (DeadLetterClaim claim : claims) {
      if (!publish(claim)) {
        break;
      }
      published++;
    }
    return published;
  }

  private boolean publish(DeadLetterClaim claim) {
    try (CorrelationScope ignored = CorrelationScope.of(claim.correlationId())) {
      DeadLetterPayload payload =
          new DeadLetterPayload(
              Topics.CASE_CHANGED,
              claim.eventId(),
              NotificationRules.DELIVERY_EXHAUSTED,
              claim.attempts(),
              null);
      EventEnvelope<DeadLetterPayload> envelope =
          EventEnvelope.create(
              NotificationRules.EVENT_TYPE_DEAD_LETTER,
              NotificationRules.DEAD_LETTER_SCHEMA_VERSION,
              claim.eventId(),
              claim.correlationId(),
              claim.failedAt() == null ? clock.instant() : claim.failedAt(),
              payload,
              clock);
      try {
        kafka
            .send(Topics.DEAD_LETTER, claim.eventId(), EventJson.write(envelope))
            .get(settings.deadLetterTimeout().toMillis(), TimeUnit.MILLISECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return false;
      } catch (Exception e) {
        LOG.warn(
            "Dead letter of notification {} not acknowledged: {}",
            claim.id(),
            e.getClass().getSimpleName());
        return false;
      }
      if (deliveries.markDeadLettered(claim.id(), claim.version()) == 0) {
        LOG.info("Dead letter of notification {} published, but its lease was lost", claim.id());
      } else {
        LOG.warn(
            "Notification {} dead-lettered after {} attempts: {}",
            claim.id(),
            claim.attempts(),
            NotificationRules.DELIVERY_EXHAUSTED);
      }
      return true;
    }
  }
}
