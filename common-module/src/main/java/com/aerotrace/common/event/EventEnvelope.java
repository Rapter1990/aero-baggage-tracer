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

  /**
   * Versioning rule: the {@code .vN} topic suffix is the only breaking version (a breaking change
   * means a new {@code .v2} topic). {@code schemaVersion} is an additive revision inside that
   * topic, so consumers must accept higher values and may only demand a minimum, for example when
   * they need a field that older revisions lack.
   */
  public void requireSchemaVersionAtLeast(int minRequired) {
    if (schemaVersion < minRequired) {
      throw new UnsupportedSchemaVersionException(eventType, schemaVersion, minRequired);
    }
  }
}
