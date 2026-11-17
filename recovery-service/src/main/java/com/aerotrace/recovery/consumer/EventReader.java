package com.aerotrace.recovery.consumer;

import com.aerotrace.common.event.EventEnvelope;
import com.aerotrace.common.event.EventJson;

/**
 * Reads a raw event for a consumer. Anything that cannot be read, is of another type or has no
 * usable event id is a {@link PoisonEventException}: retrying cannot change it. The messages name
 * the rule that was broken and never quote the event.
 */
final class EventReader {

  private static final int MAX_EVENT_ID_LENGTH = 128;

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
}
