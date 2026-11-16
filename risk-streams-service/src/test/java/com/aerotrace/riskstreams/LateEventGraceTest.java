package com.aerotrace.riskstreams;

import static com.aerotrace.riskstreams.support.Fixtures.at;
import static org.assertj.core.api.Assertions.assertThat;

import com.aerotrace.common.model.Checkpoint;
import com.aerotrace.common.model.RiskLevel;
import com.aerotrace.riskstreams.support.TopologyHarness;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

// UNVERIFIED against case study: grace applies to the BR-03 anomaly window and BR-02 risk state.
// Stream time is the highest occurredAt seen so far; with the 10 minute grace, a record is late
// when occurredAt < streamTime - 10 minutes. The tests move stream time to 10:20:00, so the edge
// is 10:10:00.000.
@Tag("unverified-spec")
class LateEventGraceTest {

  /** Baseline LOADED at 10:00 for BAG-1, then another bag's scan moves stream time to 10:20. */
  private static void advanceStreamTime(TopologyHarness h) {
    h.pipe(h.scan(Checkpoint.LOADED, at("10:00:00")));
    h.pipe(h.scan(Checkpoint.CHECK_IN, at("10:20:00")).bag("OTHER-BAG"));
  }

  private static void threeRejectionsAt(TopologyHarness h, Instant time) {
    h.pipe(
        h.scan(Checkpoint.CHECK_IN, time),
        h.scan(Checkpoint.SORTING, time),
        h.scan(Checkpoint.TRANSFER, time));
  }

  @Test
  @DisplayName("a record exactly at the grace edge is accepted into the anomaly window")
  void recordAtGraceEdgeIsAccepted() {
    // UNVERIFIED against case study
    try (TopologyHarness h = new TopologyHarness()) {
      advanceStreamTime(h);

      threeRejectionsAt(h, at("10:10:00.000"));

      var anomalies = h.anomalies();
      assertThat(anomalies).hasSize(1);
      assertThat(anomalies.getFirst().payload().windowStart()).isEqualTo(at("10:10:00"));
      assertThat(h.counter(RiskStreamsMetrics.LATE_RECORDS)).isZero();
    }
  }

  @Test
  @DisplayName("a record 1 ms past the grace edge is excluded from the anomaly window and counted")
  void recordPastGraceIsExcludedFromAnomaly() {
    // UNVERIFIED against case study
    try (TopologyHarness h = new TopologyHarness()) {
      advanceStreamTime(h);

      threeRejectionsAt(h, at("10:09:59.999"));

      assertThat(h.anomalies()).isEmpty();
      assertThat(h.counter(RiskStreamsMetrics.LATE_RECORDS)).isEqualTo(3.0);
      // Late records are measured, not dead-lettered.
      assertThat(h.deadLetters()).isEmpty();
    }
  }

  @Test
  @DisplayName("late records do not count toward the window, on-time ones still do")
  void lateRecordsAreNotMixedIntoTheCount() {
    // UNVERIFIED against case study
    try (TopologyHarness h = new TopologyHarness()) {
      advanceStreamTime(h);

      h.pipe(
          h.scan(Checkpoint.CHECK_IN, at("10:09:59.999")),
          h.scan(Checkpoint.CHECK_IN, at("10:10:00.000")),
          h.scan(Checkpoint.CHECK_IN, at("10:10:01.000")));
      assertThat(h.anomalies()).isEmpty();

      h.pipe(h.scan(Checkpoint.CHECK_IN, at("10:10:02.000")));
      var anomalies = h.anomalies();
      assertThat(anomalies).hasSize(1);
      assertThat(anomalies.getFirst().payload().rejectedTransitions()).isEqualTo(3);
      assertThat(h.counter(RiskStreamsMetrics.LATE_RECORDS)).isEqualTo(1.0);
    }
  }

  @Test
  @DisplayName("a record past grace still updates risk state (loading confirmation clears HIGH)")
  void lateRecordStillUpdatesRisk() {
    // UNVERIFIED against case study
    try (TopologyHarness h =
        new TopologyHarness(RiskStreamsProperties.defaults(), at("10:20:00"))) {
      h.pipe(h.scan(Checkpoint.CHECK_IN, at("10:20:00")).departure(at("10:25:00")));
      assertThat(h.risk()).hasSize(1);

      // Stream time is 10:20:00; this LOADED happened at 10:09:59.999, past the grace.
      h.pipe(h.scan(Checkpoint.LOADED, at("10:09:59.999")).departure(at("10:25:00")));

      var risk = h.risk();
      assertThat(risk).hasSize(1);
      assertThat(risk.getFirst().payload().previous()).isEqualTo(RiskLevel.HIGH);
      assertThat(risk.getFirst().payload().current()).isEqualTo(RiskLevel.LOW);
      assertThat(h.counter(RiskStreamsMetrics.LATE_RECORDS)).isEqualTo(1.0);
      assertThat(h.anomalies()).isEmpty();
      assertThat(h.deadLetters()).isEmpty();
    }
  }
}
