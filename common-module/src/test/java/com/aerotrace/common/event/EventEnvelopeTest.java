package com.aerotrace.common.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aerotrace.common.model.RecoveryState;
import com.aerotrace.common.model.RiskLevel;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class EventEnvelopeTest {

  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-11-03T10:00:00Z"), ZoneOffset.UTC);
  private static final Instant OCCURRED = Instant.parse("2026-11-03T09:00:00Z");

  @Test
  void roundTripPreservesAllFields() {
    var payload =
        new RiskChangedPayload(
            "AB123456",
            "LEG-2",
            RiskLevel.LOW,
            RiskLevel.HIGH,
            Instant.parse("2026-11-03T11:05:00Z"));
    var envelope =
        EventEnvelope.create(
            Topics.RISK_CHANGED, 1, "AB123456:LEG-2", "corr-9", OCCURRED, payload, CLOCK);

    String json = EventJson.write(envelope);
    EventEnvelope<RiskChangedPayload> back = EventJson.read(json, RiskChangedPayload.class);

    assertThat(back).isEqualTo(envelope);
    assertThat(back.publishedAt()).isEqualTo(Instant.parse("2026-11-03T10:00:00Z"));
    assertThat(back.payload().current()).isEqualTo(RiskLevel.HIGH);
  }

  @Test
  void jsonUsesIsoTimestampsAndAllEnvelopeFields() {
    var payload = new CaseChangedPayload("c1", "AB123456", "LEG-2", RecoveryState.OPEN, 1, "init");
    var envelope =
        EventEnvelope.create(Topics.CASE_CHANGED, 1, "c1", "corr", OCCURRED, payload, CLOCK);
    String json = EventJson.write(envelope);
    assertThat(json)
        .contains("\"eventId\"", "\"eventType\":\"recovery.case.changed.v1\"")
        .contains("\"schemaVersion\":1", "\"aggregateId\":\"c1\"")
        .contains("\"occurredAt\":\"2026-11-03T09:00:00Z\"")
        .contains("\"publishedAt\":\"2026-11-03T10:00:00Z\"")
        .contains("\"correlationId\":\"corr\"", "\"payload\"");
  }

  @Test
  void toleratesUnknownFieldsForForwardCompatibility() {
    String json =
        "{\"eventId\":\"e1\",\"eventType\":\"recovery.capacity.changed.v1\",\"schemaVersion\":2,"
            + "\"aggregateId\":\"r1\",\"occurredAt\":\"2026-11-03T09:00:00Z\","
            + "\"publishedAt\":\"2026-11-03T09:00:01Z\",\"correlationId\":\"c\","
            + "\"newTopLevelField\":true,"
            + "\"payload\":{\"resourceId\":\"r1\",\"totalSlots\":10,\"remainingSlots\":4,\"extra\":\"x\"}}";
    EventEnvelope<CapacityChangedPayload> env = EventJson.read(json, CapacityChangedPayload.class);
    assertThat(env.schemaVersion()).isEqualTo(2);
    assertThat(env.payload().remainingSlots()).isEqualTo(4);
  }

  @Test
  void newerSchemaRevisionsAreAcceptedButTooOldOnesCanBeRejected() {
    var v3 =
        EventEnvelope.create(
            Topics.CAPACITY_CHANGED,
            3,
            "r1",
            "c",
            OCCURRED,
            new CapacityChangedPayload("r1", 10, 4),
            CLOCK);
    // additive revisions within a topic are never dead-lettered
    v3.requireSchemaVersionAtLeast(1);
    v3.requireSchemaVersionAtLeast(3);
    assertThatThrownBy(() -> v3.requireSchemaVersionAtLeast(4))
        .isInstanceOf(UnsupportedSchemaVersionException.class)
        .hasMessageContaining("3")
        .hasMessageContaining("at least 4");
  }

  @Test
  void unknownEnumValuesReadAsNullInsteadOfFailing() {
    String json =
        "{\"eventId\":\"e\",\"eventType\":\"baggage.risk.changed.v1\",\"schemaVersion\":2,"
            + "\"aggregateId\":\"a\",\"occurredAt\":\"2026-11-03T09:00:00Z\","
            + "\"publishedAt\":\"2026-11-03T09:00:01Z\",\"correlationId\":\"c\","
            + "\"payload\":{\"bagTag\":\"AB123456\",\"legId\":\"LEG-2\","
            + "\"previous\":\"LOW\",\"current\":\"CRITICAL\","
            + "\"departureAt\":\"2026-11-03T11:05:00Z\"}}";

    EventEnvelope<RiskChangedPayload> env = EventJson.read(json, RiskChangedPayload.class);

    assertThat(env.payload().previous()).isEqualTo(RiskLevel.LOW);
    assertThat(env.payload().current()).isNull();
  }

  @Test
  void requiresMandatoryFields() {
    assertThatThrownBy(
            () -> EventEnvelope.create(Topics.SCAN_RECEIVED, 1, null, "c", OCCURRED, "p", CLOCK))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(
            () -> EventEnvelope.create(Topics.SCAN_RECEIVED, 0, "a", "c", OCCURRED, "p", CLOCK))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void topicsAreVersionedAndComplete() {
    assertThat(Topics.ALL)
        .containsExactlyInAnyOrder(
            "baggage.scan.received.v1",
            "baggage.scan.anomaly.v1",
            "baggage.risk.changed.v1",
            "recovery.case.changed.v1",
            "recovery.capacity.changed.v1",
            "baggage.dead-letter.v1");
    assertThat(Topics.ALL).allMatch(t -> t.endsWith(".v1"));
  }

  @Test
  void scanPayloadRoundTripKeepsSourceTimezone() {
    var p =
        new ScanReceivedPayload(
            "EV-1001",
            "AB123456",
            "FRA",
            "TRANSFER_SORTER",
            37,
            Instant.parse("2026-11-03T10:48:00Z"),
            Instant.parse("2026-11-03T11:48:00Z"),
            "Europe/Berlin",
            Instant.parse("2026-11-03T10:48:02Z"),
            false);
    var env =
        EventEnvelope.create(Topics.SCAN_RECEIVED, 1, "AB123456", "c", p.occurredAt(), p, CLOCK);
    var back = EventJson.read(EventJson.write(env), ScanReceivedPayload.class);
    assertThat(back.payload().sourceTimezone()).isEqualTo("Europe/Berlin");
    assertThat(back.payload().deviceSequence()).isEqualTo(37);
  }
}
