package com.aerotrace.recovery.consumer;

import com.aerotrace.common.event.DeadLetterPayload;
import com.aerotrace.common.event.EventEnvelope;
import com.aerotrace.common.event.EventJson;
import com.aerotrace.common.event.Topics;
import com.aerotrace.recovery.config.RecoveryProperties;
import com.aerotrace.recovery.constant.RecoveryRules;
import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;
import tools.jackson.databind.JsonNode;

/**
 * Retry, dead-letter and poison-message policy of the consumers:
 *
 * <ul>
 *   <li>a failure that may pass (the database is briefly down) is retried with a doubling wait, a
 *       few times, and only then dead-lettered;
 *   <li>a {@link PoisonEventException} can never pass, so it is dead-lettered at once;
 *   <li>a dead-lettered event becomes an envelope on {@code baggage.dead-letter.v1}, keyed by its
 *       event id (or its topic, partition and offset when it has none), for an operator to inspect
 *       and replay; the consumer then moves on, so one bad event never blocks the partition.
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
class KafkaConsumerConfiguration {

  private static final Logger LOG = LoggerFactory.getLogger(KafkaConsumerConfiguration.class);
  private static final long DEAD_LETTER_TIMEOUT_SECONDS = 5;

  @Bean
  DefaultErrorHandler kafkaErrorHandler(
      KafkaTemplate<String, String> kafka, Clock clock, RecoveryProperties properties) {
    RecoveryProperties.Consumers consumers = properties.consumers();
    ExponentialBackOffWithMaxRetries backOff =
        new ExponentialBackOffWithMaxRetries(Math.max(consumers.retryAttempts() - 1, 0));
    backOff.setInitialInterval(consumers.retryBackoff().toMillis());
    backOff.setMultiplier(2.0);
    backOff.setMaxInterval(5_000);
    DefaultErrorHandler handler = new DefaultErrorHandler(deadLetter(kafka, clock), backOff);
    handler.addNotRetryableExceptions(PoisonEventException.class);
    return handler;
  }

  private static ConsumerRecordRecoverer deadLetter(
      KafkaTemplate<String, String> kafka, Clock clock) {
    return (ConsumerRecord<?, ?> record, Exception failure) -> {
      String raw = record.value() == null ? null : record.value().toString();
      String eventId = eventIdOf(raw);
      String key =
          eventId != null
              ? eventId
              : record.topic() + "-" + record.partition() + "-" + record.offset();
      Throwable cause = failure.getCause() != null ? failure.getCause() : failure;
      // The reason is the rule that was broken or the failure's class; never the event's content.
      String reason =
          cause instanceof PoisonEventException
              ? cause.getMessage()
              : cause.getClass().getSimpleName();
      DeadLetterPayload payload = new DeadLetterPayload(record.topic(), eventId, reason, 1, raw);
      EventEnvelope<DeadLetterPayload> envelope =
          EventEnvelope.create(
              RecoveryRules.EVENT_TYPE_DEAD_LETTER,
              RecoveryRules.DEAD_LETTER_SCHEMA_VERSION,
              key,
              null,
              Instant.now(clock),
              payload,
              clock);
      LOG.warn(
          "Dead-lettering {}-{}@{}: {}",
          record.topic(),
          record.partition(),
          record.offset(),
          reason);
      try {
        kafka
            .send(Topics.DEAD_LETTER, key, EventJson.write(envelope))
            .get(DEAD_LETTER_TIMEOUT_SECONDS, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("interrupted while dead-lettering", e);
      } catch (Exception e) {
        // Rethrown so the error handler does not commit past an event that was not preserved.
        throw new IllegalStateException("could not write the dead letter", e);
      }
    };
  }

  /** The event id of a raw event, or null when it cannot be read. */
  private static String eventIdOf(String raw) {
    if (raw == null) {
      return null;
    }
    try {
      JsonNode id = EventJson.mapper().readTree(raw).get("eventId");
      return id == null || id.isNull() ? null : id.asString();
    } catch (RuntimeException notJson) {
      return null;
    }
  }
}
