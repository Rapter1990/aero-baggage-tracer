package com.aerotrace.common.event;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Versioned envelope wrapping every Kafka event (spec section 8). */
public record EventEnvelope<T>(
    String eventId,
    String eventType,
    int schemaVersion,
    String aggregateId,
    Instant occurredAt,
    Instant publishedAt,
    String correlationId,
    T payload) {

  public EventEnvelope {
    Objects.requireNonNull(eventId, "eventId");
    Objects.requireNonNull(eventType, "eventType");
    Objects.requireNonNull(aggregateId, "aggregateId");
    Objects.requireNonNull(occurredAt, "occurredAt");
    Objects.requireNonNull(publishedAt, "publishedAt");
    Objects.requireNonNull(payload, "payload");
    if (schemaVersion < 1) {
      throw new IllegalArgumentException("schemaVersion must be >= 1");
    }
  }

  /** Creates an envelope with a fresh eventId and publishedAt taken from the clock. */
  public static <T> EventEnvelope<T> create(
      String eventType,
      int schemaVersion,
      String aggregateId,
      String correlationId,
      Instant occurredAt,
      T payload,
      Clock clock) {
    return new EventEnvelope<>(
        UUID.randomUUID().toString(),
        eventType,
        schemaVersion,
        aggregateId,
        occurredAt,
        Instant.now(clock),
        correlationId,
        payload);
  }

  /** Consumers call this first; newer majors must go to the dead-letter topic, not be guessed. */
  public void requireSchemaVersionAtMost(int maxSupported) {
    if (schemaVersion > maxSupported) {
      throw new UnsupportedSchemaVersionException(eventType, schemaVersion, maxSupported);
    }
  }
}
