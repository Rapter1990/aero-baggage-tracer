package com.aerotrace.notification.consumer;

import com.aerotrace.common.correlation.CorrelationId;
import com.aerotrace.common.event.DeadLetterPayload;
import com.aerotrace.common.event.EventEnvelope;
import com.aerotrace.common.event.EventJson;
import com.aerotrace.common.event.Topics;
import com.aerotrace.notification.config.NotificationProperties;
import com.aerotrace.notification.constant.KafkaConstants;
import com.aerotrace.notification.constant.MetricNames;
import com.aerotrace.notification.constant.NotificationRules;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
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
import tools.jackson.databind.node.ObjectNode;

/**
 * Retry, dead-letter and poison-message policy of the consumer (ADR 0005, decision 7):
 *
 * <ul>
 *   <li>a failure that may pass (the database is failing over) is retried with a doubling wait,
 *       about a minute in total with the defaults, and only then dead-lettered with {@code
 *       attempts} = the configured retry attempts;
 *   <li>a {@link PoisonEventException} can never pass, so it is dead-lettered at once ({@code
 *       attempts} = 1);
 *   <li>a dead-lettered event becomes an envelope on {@code baggage.dead-letter.v1}, keyed by its
 *       event id (or its topic, partition and offset when it has no safe one), with a copy of the
 *       raw event for an operator to inspect and replay. The copy never carries the case's
 *       free-text {@code reason} (see {@link #rawCopy}). The raw event is never stored in the table
 *       and never logged. The consumer then moves on, so one bad event never blocks the partition.
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
class KafkaConsumerConfiguration {

  private static final Logger LOG = LoggerFactory.getLogger(KafkaConsumerConfiguration.class);

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
        new DefaultErrorHandler(deadLetter(kafka, clock, meters, consumers), backOff);
    handler.addNotRetryableExceptions(PoisonEventException.class);
    return handler;
  }

  private static ConsumerRecordRecoverer deadLetter(
      KafkaTemplate<String, String> kafka,
      Clock clock,
      MeterRegistry meters,
      NotificationProperties.Consumers consumers) {
    return (ConsumerRecord<?, ?> record, Exception failure) -> {
      String original = record.value() == null ? null : record.value().toString();
      JsonNode tree = tree(original);
      String eventId = text(tree, "eventId");
      // Only a safe id becomes the key and originalEventId; anything else stays in the raw copy.
      if (!NotificationRules.isSafeEventId(eventId)) {
        eventId = null;
      }
      String correlationId = text(tree, "correlationId");
      String key =
          eventId != null
              ? eventId
              : record.topic() + "-" + record.partition() + "-" + record.offset();
      Throwable cause = failure.getCause() != null ? failure.getCause() : failure;
      boolean poison = cause instanceof PoisonEventException;
      // The reason is the rule that was broken or the failure's class; never the event's content.
      String reason = poison ? cause.getMessage() : cause.getClass().getSimpleName();
      // A poison event was delivered once; a transient failure used every configured delivery.
      int attempts = poison ? 1 : Math.max(consumers.retryAttempts(), 1);
      DeadLetterPayload payload =
          new DeadLetterPayload(record.topic(), eventId, reason, attempts, rawCopy(original, tree));
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
            .get(consumers.deadLetterTimeout().toMillis(), TimeUnit.MILLISECONDS);
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

  /** {@link #rawCopy(String, JsonNode)} of a raw event that has not been parsed yet. */
  static String rawCopy(String original) {
    return rawCopy(original, tree(original));
  }

  /**
   * The copy of a bad event for its dead letter, at most {@link
   * KafkaConstants#MAX_RAW_PAYLOAD_CHARS} long (the envelope must stay sendable: escaping makes the
   * text longer, so an event near the size limit would otherwise never fit and would block its
   * partition).
   *
   * <ul>
   *   <li>Readable JSON: every {@code reason} field, at any depth, is removed; the rest is kept.
   *   <li>Unreadable text that mentions a reason: not copied at all (null), since the field cannot
   *       be cut out reliably.
   *   <li>Other unreadable text: copied as it is.
   * </ul>
   */
  private static String rawCopy(String original, JsonNode tree) {
    if (original == null) {
      return null;
    }
    if (tree != null && (tree.isObject() || tree.isArray())) {
      JsonNode copy = tree.deepCopy();
      dropReasons(copy);
      try {
        return truncate(EventJson.mapper().writeValueAsString(copy));
      } catch (RuntimeException unwritable) {
        // Treated as unreadable below.
      }
    }
    if (original.toLowerCase(Locale.ROOT).contains(KafkaConstants.REASON_FIELD)) {
      return null;
    }
    return truncate(original);
  }

  private static void dropReasons(JsonNode node) {
    if (node instanceof ObjectNode object) {
      object.remove(KafkaConstants.REASON_FIELD);
      for (Map.Entry<String, JsonNode> field : object.properties()) {
        dropReasons(field.getValue());
      }
    } else if (node != null && node.isArray()) {
      for (JsonNode element : node) {
        dropReasons(element);
      }
    }
  }

  static String truncate(String raw) {
    if (raw == null || raw.length() <= KafkaConstants.MAX_RAW_PAYLOAD_CHARS) {
      return raw;
    }
    return raw.substring(0, KafkaConstants.MAX_RAW_PAYLOAD_CHARS);
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
