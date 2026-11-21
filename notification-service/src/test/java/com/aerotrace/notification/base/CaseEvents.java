package com.aerotrace.notification.base;

import com.aerotrace.common.event.CaseChangedPayload;
import com.aerotrace.common.event.EventEnvelope;
import com.aerotrace.common.event.EventJson;
import com.aerotrace.common.model.CaseTrigger;
import com.aerotrace.common.model.RecoveryState;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/** Builds {@code recovery.case.changed.v1} events as the recovery-service writes them. */
public final class CaseEvents {

  public static final String EVENT_TYPE = "recovery.case.changed";

  /** The correlation id every test event carries. */
  public static final String CORRELATION_ID = "corr-test";

  private static final Instant AT = Instant.parse("2026-03-01T09:00:00Z");

  private CaseEvents() {}

  public static String eventId() {
    return "EV-" + UUID.randomUUID();
  }

  public static String bagTag() {
    return String.format("%010d", ThreadLocalRandom.current().nextLong(10_000_000_000L));
  }

  /** A case id as the recovery-service writes it: a UUID (anything else is poison). */
  public static String caseId() {
    return UUID.randomUUID().toString();
  }

  /** A unique handler id, so tests that share one database never see each other's rows. */
  public static String handler() {
    return "handler-" + UUID.randomUUID();
  }

  /** A revision 2 event: assigned to {@code assignedTo}, with a free-text reason. */
  public static String event(
      String eventId,
      String caseId,
      String bagTag,
      RecoveryState state,
      long version,
      String reason,
      String assignedTo) {
    return write(eventId, 2, caseId, bagTag, state, version, reason, assignedTo);
  }

  /** A revision 1 event: it has none of the revision 2 fields (no assignedTo, no trigger). */
  public static String revision1(
      String eventId, String caseId, String bagTag, RecoveryState state, long version) {
    return EventJson.write(
        new EventEnvelope<>(
            eventId,
            EVENT_TYPE,
            1,
            caseId,
            AT,
            AT,
            CORRELATION_ID,
            new CaseChangedPayload(
                caseId,
                bagTag,
                UUID.randomUUID().toString(),
                state,
                version,
                "HIGH_CONNECTION_RISK",
                null,
                null,
                null,
                null)));
  }

  public static String write(
      String eventId,
      int schemaVersion,
      String caseId,
      String bagTag,
      RecoveryState state,
      long version,
      String reason,
      String assignedTo) {
    return EventJson.write(
        new EventEnvelope<>(
            eventId,
            EVENT_TYPE,
            schemaVersion,
            caseId,
            AT,
            AT,
            CORRELATION_ID,
            new CaseChangedPayload(
                caseId,
                bagTag,
                UUID.randomUUID().toString(),
                state,
                version,
                reason,
                AT,
                state == RecoveryState.RESOLVED ? AT : null,
                CaseTrigger.RISK,
                assignedTo)));
  }
}
