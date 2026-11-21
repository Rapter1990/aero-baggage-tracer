package com.aerotrace.notification.consumer;

import com.aerotrace.common.correlation.CorrelationId;
import com.aerotrace.common.event.DeadLetterPayload;
import com.aerotrace.common.event.EventEnvelope;
import com.aerotrace.common.event.EventJson;
import com.aerotrace.common.event.Topics;
import com.aerotrace.notification.config.NotificationProperties;
import com.aerotrace.notification.constant.MetricNames;
import com.aerotrace.notification.constant.NotificationRules;
import io.micrometer.core.instrument.MeterRegistry;
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
 * Retry, dead-letter and poison-message policy of the consumer (ADR 0005, decision 7):
 *
 * <ul>
 *   <li>a failure that may pass (the database is failing over) is retried with a doubling wait,
 *       about a minute in total with the defaults, and only then dead-lettered;
 *   <li>a {@link PoisonEventException} can never pass, so it is dead-lettered at once;
 *   <li>a dead-lettered event becomes an envelope on {@code baggage.dead-letter.v1}, keyed by its
 *       event id (or its topic, partition and offset when it has none), with the raw event for an
 *       operator to inspect and replay, as in the recovery-service. The raw event is never stored
 *       in the table and never logged. The consumer then moves on, so one bad event never blocks
 *       the partition.
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
class KafkaConsumerConfiguration {

  private static final Logger LOG = LoggerFactory.getLogger(KafkaConsumerConfiguration.class);
  private static final long DEAD_LETTER_TIMEOUT_SECONDS = 5;

  /** How much of a bad event is kept on the dead-letter topic. */
  static final int MAX_RAW_PAYLOAD_CHARS = 16_384;

  /** The longest key a dead letter gets. */
  static final int MAX_KEY_CHARS = 128;

  @Bean
  DefaultErrorHandler kafkaErrorHandler(
      KafkaTemplate<String, String> kafka,
      Clock clock,
      NotificationProperties properties,
      MeterRegistry meters) {
    NotificationProperties.Consumers consumers = properties.consumers();
    ExponentialBackOffWithMaxRetries backOff =
        new ExponentialBackOffWithMaxRetries(Math.max(consumers.retryAttempts() - 1, 0));
    backOff.setInitialInterval(consumers.retryBackoff().toMillis());
    backOff.setMultiplier(2.0);
    backOff.setMaxInterval(consumers.retryMaxBackoff().toMillis());
    DefaultErrorHandler handler =
        new DefaultErrorHandler(deadLetter(kafka, clock, meters), backOff);
    handler.addNotRetryableExceptions(PoisonEventException.class);
    return handler;
  }

  private static ConsumerRecordRecoverer deadLetter(
      KafkaTemplate<String, String> kafka, Clock clock, MeterRegistry meters) {
    return (ConsumerRecord<?, ?> record, Exception failure) -> {
      String original = record.value() == null ? null : record.value().toString();
      // The envelope must stay sendable: escaping makes the raw text longer, so an event near the
      // size limit would otherwise never fit and would block its partition.
      String raw = truncate(original);
      JsonNode tree = tree(original);
      String eventId = capped(text(tree, "eventId"));
      String correlationId = text(tree, "correlationId");
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
              NotificationRules.EVENT_TYPE_DEAD_LETTER,
              NotificationRules.DEAD_LETTER_SCHEMA_VERSION,
              key,
              CorrelationId.isValid(correlationId) ? correlationId : null,
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
      meters
          .counter(MetricNames.EVENTS, MetricNames.OUTCOME, MetricNames.DEAD_LETTERED)
          .increment();
    };
  }

  static String truncate(String raw) {
    if (raw == null || raw.length() <= MAX_RAW_PAYLOAD_CHARS) {
      return raw;
    }
    return raw.substring(0, MAX_RAW_PAYLOAD_CHARS);
  }

  static String capped(String eventId) {
    if (eventId == null || eventId.length() <= MAX_KEY_CHARS) {
      return eventId;
    }
    return eventId.substring(0, MAX_KEY_CHARS);
  }

  /** The raw event as a tree, or null when it is not JSON. */
  private static JsonNode tree(String raw) {
    if (raw == null) {
      return null;
    }
    try {
      return EventJson.mapper().readTree(raw);
    } catch (RuntimeException notJson) {
      return null;
    }
  }

  private static String text(JsonNode tree, String field) {
    if (tree == null) {
      return null;
    }
    JsonNode value = tree.get(field);
    return value == null || value.isNull() || !value.isString() ? null : value.asString();
  }
}
