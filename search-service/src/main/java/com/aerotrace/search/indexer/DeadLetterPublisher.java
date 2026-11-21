package com.aerotrace.search.indexer;

import com.aerotrace.common.event.DeadLetterPayload;
import com.aerotrace.common.event.EventEnvelope;
import com.aerotrace.common.event.EventJson;
import com.aerotrace.common.event.Topics;
import com.aerotrace.search.constant.SearchTopics;
import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Writes a poison event to {@code baggage.dead-letter.v1}, the way the recovery-service's consumers
 * do: an envelope keyed by the event id (or topic, partition and offset when it has none), the raw
 * event truncated, and a reason that names the broken rule, never the content. The send is waited
 * for: if it fails, the batch fails and is retried, so an event is never skipped without a trace.
 */
@Component
public class DeadLetterPublisher {

  private static final Logger LOG = LoggerFactory.getLogger(DeadLetterPublisher.class);
  private static final long SEND_TIMEOUT_SECONDS = 5;
  static final int MAX_RAW_PAYLOAD_CHARS = 16_384;
  static final int MAX_KEY_CHARS = 128;

  private final KafkaTemplate<String, String> kafka;
  private final Clock clock;

  public DeadLetterPublisher(KafkaTemplate<String, String> kafka, Clock clock) {
    this.kafka = kafka;
    this.clock = clock;
  }

  public void publish(ConsumerRecord<String, String> record, String reason) {
    String raw = record.value();
    String eventId = capped(field(raw, "eventId"));
    String key =
        eventId != null
            ? eventId
            : record.topic() + "-" + record.partition() + "-" + record.offset();
    DeadLetterPayload payload =
        new DeadLetterPayload(record.topic(), eventId, reason, 1, truncate(raw));
    EventEnvelope<DeadLetterPayload> envelope =
        EventEnvelope.create(
            SearchTopics.EVENT_TYPE_DEAD_LETTER,
            SearchTopics.DEAD_LETTER_SCHEMA_VERSION,
            key,
            // The original correlation id travels on, so the dead letter joins its trace.
            capped(field(raw, "correlationId")),
            Instant.now(clock),
            payload,
            clock);
    LOG.warn(
        "Dead-lettering {}-{}@{}: {}", record.topic(), record.partition(), record.offset(), reason);
    try {
      kafka
          .send(Topics.DEAD_LETTER, key, EventJson.write(envelope))
          .get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted while dead-lettering", e);
    } catch (Exception e) {
      throw new IllegalStateException("could not write the dead letter", e);
    }
  }

  static String truncate(String raw) {
    if (raw == null || raw.length() <= MAX_RAW_PAYLOAD_CHARS) {
      return raw;
    }
    return raw.substring(0, MAX_RAW_PAYLOAD_CHARS);
  }

  static String capped(String value) {
    if (value == null || value.length() <= MAX_KEY_CHARS) {
      return value;
    }
    return value.substring(0, MAX_KEY_CHARS);
  }

  /** A top-level text field of a raw event, or null when it cannot be read. */
  private static String field(String raw, String name) {
    if (raw == null) {
      return null;
    }
    try {
      JsonNode node = EventJson.mapper().readTree(raw).get(name);
      return node == null || node.isNull() ? null : node.asString();
    } catch (RuntimeException notJson) {
      return null;
    }
  }
}
