package com.aerotrace.common.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aerotrace.common.model.AnomalyType;
import com.aerotrace.common.model.RiskLevel;
import java.lang.reflect.Modifier;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Issue #15: the wire contract of every topic. A payload round-trips, a revision-1 event still
 * reads, a field a newer producer adds is ignored, and the topic list stays complete and versioned.
 */
class TopicContractTest {

  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-11-03T10:00:00Z"), ZoneOffset.UTC);
  private static final Instant OCCURRED = Instant.parse("2026-11-03T09:00:00Z");

  private static <T> EventEnvelope<T> envelope(String type, String aggregateId, T payload) {
    return EventEnvelope.create(type, 1, aggregateId, "corr-1", OCCURRED, payload, CLOCK);
  }

  @Test
  @DisplayName("Topics.ALL lists every topic constant once, and each name ends in .v1")
  void topicListIsCompleteAndVersioned() {
    List<String> declared =
        Arrays.stream(Topics.class.getDeclaredFields())
            .filter(f -> Modifier.isPublic(f.getModifiers()) && f.getType() == String.class)
            .map(
                f -> {
                  try {
                    return (String) f.get(null);
                  } catch (IllegalAccessException e) {
                    throw new IllegalStateException(e);
                  }
                })
            .toList();

    assertThat(Topics.ALL).containsExactlyInAnyOrderElementsOf(declared);
    assertThat(new HashSet<>(Topics.ALL)).hasSameSizeAs(Topics.ALL).hasSize(7);
    assertThat(Topics.ALL).allSatisfy(topic -> assertThat(topic).endsWith(".v1"));
  }

  @Test
  @DisplayName("baggage.scan.received.v1 round-trips; a revision 1 event reads with no leg")
  void scanReceived() {
    var payload =
        new ScanReceivedPayload(
            "scan-1",
            "0123456789",
            "IST",
            "CHECK_IN",
            3,
            OCCURRED,
            OCCURRED,
            "Europe/Istanbul",
            Instant.parse("2026-11-03T09:00:05Z"),
            false,
            "leg-1",
            "TK1981",
            Instant.parse("2026-11-04T08:00:00Z"));
    var event = envelope("baggage.scan.received", "0123456789", payload);

    assertThat(EventJson.read(EventJson.write(event), ScanReceivedPayload.class)).isEqualTo(event);

    String revision1 =
        """
        {"eventId":"e-1","eventType":"baggage.scan.received","schemaVersion":1,
         "aggregateId":"0123456789","occurredAt":"2026-11-03T09:00:00Z",
         "publishedAt":"2026-11-03T09:00:05Z","correlationId":"c-1",
         "payload":{"scanEventId":"scan-1","bagTag":"0123456789","airport":"IST",
          "checkpoint":"CHECK_IN","deviceSequence":3,"occurredAt":"2026-11-03T09:00:00Z",
          "deviceTimestamp":"2026-11-03T09:00:00Z","sourceTimezone":"Europe/Istanbul",
          "ingestedAt":"2026-11-03T09:00:05Z","lateEvent":false}}
        """;
    ScanReceivedPayload old = EventJson.read(revision1, ScanReceivedPayload.class).payload();
    assertThat(old.bagTag()).isEqualTo("0123456789");
    assertThat(old.legId()).isNull();
    assertThat(old.flightNumber()).isNull();
    assertThat(old.departureAt()).isNull();
  }

  @Test
  @DisplayName("baggage.risk.changed.v1 round-trips and is keyed bagTag:legId")
  void riskChanged() {
    var payload =
        new RiskChangedPayload(
            "0123456789",
            "leg-1",
            RiskLevel.LOW,
            RiskLevel.HIGH,
            Instant.parse("2026-11-04T08:00:00Z"));
    var event = envelope("baggage.risk.changed", "0123456789:leg-1", payload);

    String json = EventJson.write(event);

    assertThat(json).contains("\"previous\":\"LOW\"", "\"current\":\"HIGH\"");
    assertThat(EventJson.read(json, RiskChangedPayload.class)).isEqualTo(event);
    assertThat(event.aggregateId()).isEqualTo(payload.bagTag() + ":" + payload.legId());
  }

  @Test
  @DisplayName("baggage.scan.anomaly.v1 round-trips")
  void scanAnomaly() {
    var payload =
        new ScanAnomalyPayload(
            "0123456789",
            AnomalyType.SCANNER_ANOMALY,
            3,
            Instant.parse("2026-11-03T09:00:00Z"),
            Instant.parse("2026-11-03T09:05:00Z"));
    var event = envelope("baggage.scan.anomaly", "0123456789", payload);

    String json = EventJson.write(event);

    assertThat(json).contains("\"anomalyType\":\"SCANNER_ANOMALY\"");
    assertThat(EventJson.read(json, ScanAnomalyPayload.class)).isEqualTo(event);
  }

  @Test
  @DisplayName("recovery.capacity.changed.v1 round-trips")
  void capacityChanged() {
    var event =
        envelope(
            "recovery.capacity.changed", "cart-1", new CapacityChangedPayload("cart-1", 10, 4));

    assertThat(EventJson.read(EventJson.write(event), CapacityChangedPayload.class))
        .isEqualTo(event);
  }

  @Test
  @DisplayName("baggage.dead-letter.v1 keeps the original payload as raw text, quotes included")
  void deadLetter() {
    String raw = "{\"not\":\"an envelope\",\"quote\":\"a \\\"b\\\"\"}";
    var event =
        envelope(
            "baggage.dead-letter",
            "e-1",
            new DeadLetterPayload("recovery.case.changed.v1", "e-1", "poison", 5, raw));

    DeadLetterPayload read =
        EventJson.read(EventJson.write(event), DeadLetterPayload.class).payload();

    assertThat(read.rawPayload()).isEqualTo(raw);
    assertThat(read.attempts()).isEqualTo(5);
    assertThat(read.originalTopic()).isEqualTo(Topics.CASE_CHANGED);
  }

  @Test
  @DisplayName("a field a newer producer adds is ignored, an unknown enum constant reads as null")
  void tolerantReader() {
    String json =
        """
        {"eventId":"e-1","eventType":"baggage.risk.changed","schemaVersion":3,
         "aggregateId":"0123456789:leg-1","occurredAt":"2026-11-03T09:00:00Z",
         "publishedAt":"2026-11-03T09:00:05Z","correlationId":"c-1","addedByRevision3":true,
         "payload":{"bagTag":"0123456789","legId":"leg-1","previous":"LOW","current":"CRITICAL",
          "departureAt":"2026-11-04T08:00:00Z","newField":42}}
        """;

    EventEnvelope<RiskChangedPayload> event = EventJson.read(json, RiskChangedPayload.class);

    assertThat(event.payload().previous()).isEqualTo(RiskLevel.LOW);
    assertThat(event.payload().current()).isNull();
    event.requireSchemaVersionAtLeast(1);
    assertThatThrownBy(() -> event.requireSchemaVersionAtLeast(4))
        .isInstanceOf(UnsupportedSchemaVersionException.class);
  }
}
