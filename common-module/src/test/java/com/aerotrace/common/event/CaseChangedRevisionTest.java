package com.aerotrace.common.event;

import static org.assertj.core.api.Assertions.assertThat;

import com.aerotrace.common.model.CaseTrigger;
import com.aerotrace.common.model.RecoveryState;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The contract of {@code recovery.case.changed.v1}: revision 2 adds {@code openedAt}, {@code
 * resolvedAt}, {@code trigger} and {@code assignedTo}, and a revision 1 event still reads, with
 * those fields as {@code null}.
 */
class CaseChangedRevisionTest {

  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-11-03T10:00:00Z"), ZoneOffset.UTC);
  private static final Instant OPENED = Instant.parse("2026-11-03T08:00:00Z");
  private static final Instant RESOLVED = Instant.parse("2026-11-03T09:30:00Z");

  @Test
  @DisplayName("revision 2 writes and reads openedAt, resolvedAt, trigger and assignedTo")
  void revisionTwoRoundTrip() {
    var payload =
        new CaseChangedPayload(
            "c1",
            "0123456789",
            "LEG-2",
            RecoveryState.RESOLVED,
            6,
            "delivered",
            OPENED,
            RESOLVED,
            CaseTrigger.RISK,
            "agent-7");
    var envelope =
        EventEnvelope.create(Topics.CASE_CHANGED, 2, "c1", "corr-1", RESOLVED, payload, CLOCK);

    String json = EventJson.write(envelope);
    EventEnvelope<CaseChangedPayload> back = EventJson.read(json, CaseChangedPayload.class);

    assertThat(json)
        .contains("\"schemaVersion\":2")
        .contains("\"openedAt\":\"2026-11-03T08:00:00Z\"")
        .contains("\"resolvedAt\":\"2026-11-03T09:30:00Z\"")
        .contains("\"trigger\":\"RISK\"", "\"assignedTo\":\"agent-7\"");
    assertThat(back).isEqualTo(envelope);
  }

  @Test
  @DisplayName("an open case of revision 2 has no resolvedAt and no handler yet")
  void revisionTwoOpenCase() {
    var payload =
        new CaseChangedPayload(
            "c2",
            "0123456789",
            "LEG-2",
            RecoveryState.OPEN,
            1,
            "opened",
            OPENED,
            null,
            CaseTrigger.MANUAL,
            null);
    var envelope =
        EventEnvelope.create(Topics.CASE_CHANGED, 2, "c2", "corr", OPENED, payload, CLOCK);
    String json = EventJson.write(envelope);

    assertThat(json).contains("\"resolvedAt\":null", "\"assignedTo\":null");
    assertThat(EventJson.read(json, CaseChangedPayload.class)).isEqualTo(envelope);
  }

  @Test
  @DisplayName("a revision 1 event still reads: the revision 2 fields are null")
  void revisionOneStillReads() {
    String v1 =
        "{\"eventId\":\"e1\",\"eventType\":\"recovery.case.changed\",\"schemaVersion\":1,"
            + "\"aggregateId\":\"c1\",\"occurredAt\":\"2026-11-03T09:00:00Z\","
            + "\"publishedAt\":\"2026-11-03T09:00:01Z\",\"correlationId\":\"c\","
            + "\"payload\":{\"caseId\":\"c1\",\"bagTag\":\"0123456789\",\"legId\":\"LEG-2\","
            + "\"state\":\"ASSESSING\",\"version\":2,\"reason\":\"checking\"}}";

    EventEnvelope<CaseChangedPayload> env = EventJson.read(v1, CaseChangedPayload.class);

    assertThat(env.schemaVersion()).isEqualTo(1);
    CaseChangedPayload c = env.payload();
    assertThat(c.caseId()).isEqualTo("c1");
    assertThat(c.state()).isEqualTo(RecoveryState.ASSESSING);
    assertThat(c.version()).isEqualTo(2);
    assertThat(c.reason()).isEqualTo("checking");
    assertThat(c.openedAt()).isNull();
    assertThat(c.resolvedAt()).isNull();
    assertThat(c.trigger()).isNull();
    assertThat(c.assignedTo()).isNull();
  }

  @Test
  @DisplayName("a trigger added by a newer producer reads as null instead of failing")
  void unknownTriggerReadsAsNull() {
    String json =
        "{\"eventId\":\"e1\",\"eventType\":\"recovery.case.changed\",\"schemaVersion\":3,"
            + "\"aggregateId\":\"c1\",\"occurredAt\":\"2026-11-03T09:00:00Z\","
            + "\"publishedAt\":\"2026-11-03T09:00:01Z\",\"correlationId\":\"c\","
            + "\"payload\":{\"caseId\":\"c1\",\"bagTag\":\"0123456789\",\"legId\":\"LEG-2\","
            + "\"state\":\"OPEN\",\"version\":1,\"reason\":\"r\","
            + "\"openedAt\":\"2026-11-03T09:00:00Z\",\"trigger\":\"SATELLITE\"}}";

    CaseChangedPayload c = EventJson.read(json, CaseChangedPayload.class).payload();

    assertThat(c.trigger()).isNull();
    assertThat(c.openedAt()).isEqualTo(Instant.parse("2026-11-03T09:00:00Z"));
  }
}
