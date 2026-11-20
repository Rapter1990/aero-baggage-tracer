package com.aerotrace.search.indexer;

import com.aerotrace.common.event.EventEnvelope;
import com.aerotrace.common.event.EventJson;

/**
 * Reads a raw event for the indexer. Anything that cannot be read, is of another type or has no
 * usable event id is a {@link PoisonEventException}: retrying cannot change it. Higher schema
 * revisions are accepted (they are additive); the messages never quote the event.
 */
final class EventReader {

  private static final int MAX_EVENT_ID_LENGTH = 100;

  private EventReader() {}

  static <T> EventEnvelope<T> read(String json, String expectedEventType, Class<T> payloadType) {
    if (json == null || json.isBlank()) {
      throw new PoisonEventException("empty event");
    }
    EventEnvelope<T> event;
    try {
      EventJson.Header header = EventJson.readHeader(json);
      if (!expectedEventType.equals(header.eventType())) {
        throw new PoisonEventException("unexpected event type");
      }
      event = EventJson.read(json, payloadType);
    } catch (PoisonEventException e) {
      throw e;
    } catch (RuntimeException unreadable) {
      throw new PoisonEventException("event is not readable", unreadable);
    }
    if (event.eventId() == null
        || event.eventId().isBlank()
        || event.eventId().length() > MAX_EVENT_ID_LENGTH) {
      throw new PoisonEventException("eventId is missing or too long");
    }
    return event;
  }

  /** A required text field: missing or blank is poison. */
  static String required(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new PoisonEventException(field + " is missing");
    }
    if (value.length() > 128) {
      throw new PoisonEventException(field + " is too long");
    }
    return value;
  }
}
