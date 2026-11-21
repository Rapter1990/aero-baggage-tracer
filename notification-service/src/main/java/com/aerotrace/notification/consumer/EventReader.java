package com.aerotrace.notification.consumer;

import com.aerotrace.common.event.CaseChangedPayload;
import com.aerotrace.common.event.EventEnvelope;
import com.aerotrace.common.event.EventJson;
import com.aerotrace.notification.constant.NotificationRules;
import java.util.UUID;

/**
 * Reads a raw {@code recovery.case.changed.v1} event. Anything that breaks the contract is a {@link
 * PoisonEventException}: retrying cannot change it (ADR 0005, decision 7). Any schema revision is
 * accepted (revisions are additive inside the {@code .v1} topic), and a state this service does not
 * know reads as {@code null} and is not poison. The messages name the rule and never quote the
 * event.
 */
final class EventReader {

  private EventReader() {}

  static EventEnvelope<CaseChangedPayload> read(String json) {
    if (json == null || json.isBlank()) {
      throw new PoisonEventException("empty event");
    }
    EventEnvelope<CaseChangedPayload> event;
    try {
      EventJson.Header header = EventJson.readHeader(json);
      if (!NotificationRules.EVENT_TYPE_CASE_CHANGED.equals(header.eventType())) {
        throw new PoisonEventException("unexpected event type");
      }
      event = EventJson.read(json, CaseChangedPayload.class);
    } catch (PoisonEventException e) {
      throw e;
    } catch (RuntimeException unreadable) {
      // Also a missing eventId or payload: the envelope refuses to be built without them.
      throw new PoisonEventException("event is not readable", unreadable);
    }
    // The event id is logged, stored and used as a Kafka key: only short, plain ids are accepted,
    // so a CR/LF or markup in it can never forge a log line.
    if (!NotificationRules.isSafeEventId(event.eventId())) {
      throw new PoisonEventException("eventId is missing, too long or has unsafe characters");
    }
    CaseChangedPayload payload = event.payload();
    caseId(payload);
    if (payload.bagTag() == null
        || payload.bagTag().isBlank()
        || payload.bagTag().length() > NotificationRules.MAX_BAG_TAG_LENGTH) {
      throw new PoisonEventException("bagTag is missing or too long");
    }
    if (payload.version() < 0) {
      throw new PoisonEventException("version is negative");
    }
    if (payload.assignedTo() != null
        && payload.assignedTo().length() > NotificationRules.MAX_RECIPIENT_LENGTH) {
      throw new PoisonEventException("assignedTo is too long");
    }
    return event;
  }

  /** The case id of a payload that {@link #read} accepted. */
  static UUID caseId(CaseChangedPayload payload) {
    if (payload.caseId() == null) {
      throw new PoisonEventException("caseId is missing");
    }
    try {
      return UUID.fromString(payload.caseId());
    } catch (IllegalArgumentException notUuid) {
      throw new PoisonEventException("caseId is not a UUID");
    }
  }
}
