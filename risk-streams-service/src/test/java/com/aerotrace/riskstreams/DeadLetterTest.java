package com.aerotrace.riskstreams;

import static com.aerotrace.riskstreams.support.Fixtures.at;
import static org.assertj.core.api.Assertions.assertThat;

import com.aerotrace.common.event.Topics;
import com.aerotrace.common.model.Checkpoint;
import com.aerotrace.riskstreams.support.ScanBuilder;
import com.aerotrace.riskstreams.support.TopologyHarness;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Poison input becomes one dead-letter record; it never stops the stream. Not spec-dependent. */
class DeadLetterTest {

  @Test
  @DisplayName("unparseable JSON is dead-lettered with a topic-partition-offset key")
  void malformedJson() {
    try (TopologyHarness h = new TopologyHarness()) {
      h.pipeRaw("BAG-1", "this is {not json");

      var dead = h.deadLetters();
      assertThat(dead).hasSize(1);
      assertThat(dead.getFirst().key()).startsWith(Topics.SCAN_RECEIVED + "-0-");
      assertThat(dead.getFirst().payload().originalTopic()).isEqualTo(Topics.SCAN_RECEIVED);
      assertThat(dead.getFirst().payload().originalEventId()).isNull();
      // Unreadable input has no correlation id to keep, and none is invented.
      assertThat(dead.getFirst().envelope().correlationId()).isNull();
      assertThat(dead.getFirst().payload().reason())
          .isEqualTo(RiskStreamsTopology.REASON_MALFORMED_JSON);
      assertThat(dead.getFirst().payload().attempts()).isEqualTo(1);
      assertThat(dead.getFirst().payload().rawPayload()).isEqualTo("this is {not json");
      assertThat(dead.getFirst().envelope().eventType()).isEqualTo("baggage.dead-letter");
      assertThat(dead.getFirst().envelope().schemaVersion()).isEqualTo(1);
      assertThat(h.risk()).isEmpty();
      assertThat(h.anomalies()).isEmpty();
    }
  }

  @Test
  @DisplayName("a revision 1 event is dead-lettered, keyed by its eventId")
  void oldSchemaVersion() {
    try (TopologyHarness h = new TopologyHarness()) {
      ScanBuilder old = h.scan(Checkpoint.CHECK_IN, at("10:00:00")).schemaVersion(1).withoutLeg();
      h.pipe(old);

      var dead = h.deadLetters();
      assertThat(dead).hasSize(1);
      assertThat(dead.getFirst().key()).isEqualTo(old.eventId());
      assertThat(dead.getFirst().payload().originalEventId()).isEqualTo(old.eventId());
      assertThat(dead.getFirst().payload().reason())
          .isEqualTo(RiskStreamsTopology.REASON_UNSUPPORTED_SCHEMA_VERSION);
      assertThat(dead.getFirst().payload().attempts()).isEqualTo(1);
      assertThat(dead.getFirst().payload().rawPayload()).isEqualTo(old.json());
      // The dead letter stays on the trace of the record it replaces.
      assertThat(dead.getFirst().envelope().correlationId()).startsWith("corr-");
      assertThat(h.risk()).isEmpty();
    }
  }

  @Test
  @DisplayName("an unknown checkpoint is an invalid payload")
  void unknownCheckpoint() {
    try (TopologyHarness h = new TopologyHarness()) {
      ScanBuilder bad = h.scan(Checkpoint.CHECK_IN, at("10:00:00")).checkpointText("TELEPORT");
      h.pipe(bad);

      var dead = h.deadLetters();
      assertThat(dead).hasSize(1);
      assertThat(dead.getFirst().key()).isEqualTo(bad.eventId());
      assertThat(dead.getFirst().payload().reason())
          .isEqualTo(RiskStreamsTopology.REASON_INVALID_PAYLOAD);
    }
  }

  @Test
  @DisplayName("a payload without scanEventId cannot be deduplicated: invalid payload")
  void missingScanEventId() {
    try (TopologyHarness h = new TopologyHarness()) {
      ScanBuilder bad = h.scan(Checkpoint.CHECK_IN, at("10:00:00")).withoutScanEventId();
      h.pipe(bad);

      var dead = h.deadLetters();
      assertThat(dead).hasSize(1);
      assertThat(dead.getFirst().payload().reason())
          .isEqualTo(RiskStreamsTopology.REASON_INVALID_PAYLOAD);
    }
  }

  @Test
  @DisplayName("the stream keeps processing after poison records")
  void streamSurvivesPoison() {
    try (TopologyHarness h = new TopologyHarness()) {
      h.pipeRaw("BAG-1", "{");
      h.pipe(h.scan(Checkpoint.CHECK_IN, at("10:00:00")).schemaVersion(1));
      h.pipe(h.scan(Checkpoint.CHECK_IN, at("10:00:00")).departure(at("10:10:00")));

      assertThat(h.deadLetters()).hasSize(2);
      // A valid scan after the poison one is still processed (HIGH: 10 minutes to departure).
      assertThat(h.risk()).hasSize(1);
    }
  }

  @Test
  @DisplayName("an unsafe or missing correlation id is dropped, never invented")
  void correlationIdIsOnlyKeptWhenSafe() {
    assertThat(ScanProcessor.correlationIdOf("{\"correlationId\":\"corr-1\"}")).isEqualTo("corr-1");
    assertThat(ScanProcessor.correlationIdOf("{\"correlationId\":\"a b\nc\"}")).isNull();
    assertThat(ScanProcessor.correlationIdOf("{\"eventId\":\"e-1\"}")).isNull();
    assertThat(ScanProcessor.correlationIdOf("{not json")).isNull();
    assertThat(ScanProcessor.correlationIdOf(null)).isNull();
  }
}
