package com.aerotrace.recovery.consumer;

import com.aerotrace.common.event.EventEnvelope;
import com.aerotrace.common.event.EventJson;

/**
 * Reads a raw event for a consumer. Anything that cannot be read, is of another type or has no
 * usable event id is a {@link PoisonEventException}: retrying cannot change it. The messages name
 * the rule that was broken and never quote the event.
 */
final class EventReader {

  /**
   * Short enough that "eventType:eventId" (the dedupe key, see {@link #dedupeKey}) fits the 128
   * characters of processed_event.event_id: the longest event type of ours has 25.
   */
  private static final int MAX_EVENT_ID_LENGTH = 100;

  private EventReader() {}

  /**
   * The key under which an event is remembered as processed. It includes the event type, so an
   * event id that is reused (by mistake or on purpose) on another topic cannot make a real event of
   * that topic look like a duplicate.
   */
  static String dedupeKey(EventEnvelope<?> event) {
    return event.eventType() + ":" + event.eventId();
  }

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
