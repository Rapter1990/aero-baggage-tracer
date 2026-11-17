package com.aerotrace.riskstreams;

import static com.aerotrace.riskstreams.support.Fixtures.T0;
import static com.aerotrace.riskstreams.support.Fixtures.at;
import static org.assertj.core.api.Assertions.assertThat;

import com.aerotrace.common.model.Checkpoint;
import com.aerotrace.common.model.RiskLevel;
import com.aerotrace.riskstreams.support.TopologyHarness;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

// UNVERIFIED against case study: the whole class encodes BR-02 as reconstructed by the architect.
@Tag("unverified-spec")
class LegRiskTest {

  @ParameterizedTest(name = "{0} s to departure -> HIGH={1}")
  @CsvSource({
    "1200, false", // exactly at the 20 min threshold: the comparison is strict
    "1199, true", // one second inside
    "1201, false", // one second outside
    "1, true",
    "14400, false"
  })
  @DisplayName("threshold boundary: HIGH only when strictly below the threshold")
  void thresholdBoundary(long secondsToDeparture, boolean expectHigh) {
    // UNVERIFIED against case study
    try (TopologyHarness h = new TopologyHarness()) {
      h.pipe(h.scan(Checkpoint.CHECK_IN, T0).departure(T0.plusSeconds(secondsToDeparture)));

      var risk = h.risk();
      if (expectHigh) {
        assertThat(risk).hasSize(1);
        assertThat(risk.getFirst().payload().current()).isEqualTo(RiskLevel.HIGH);
      } else {
        assertThat(risk).isEmpty();
      }
    }
  }

  @Test
  @DisplayName("per-airport override replaces the default threshold, other airports keep it")
  void perAirportOverride() {
    // UNVERIFIED against case study
    RiskStreamsProperties props =
        RiskStreamsProperties.defaults().withAirportThreshold("IST", Duration.ofMinutes(45));
    try (TopologyHarness h = new TopologyHarness(props)) {
      // 30 minutes to departure: HIGH at IST (45), not at SAW (default 20).
      h.pipe(
          h.scan(Checkpoint.CHECK_IN, T0)
              .bag("IST-BAG")
              .airport("IST")
              .departure(T0.plusSeconds(30 * 60)),
          h.scan(Checkpoint.CHECK_IN, T0)
              .bag("SAW-BAG")
              .airport("SAW")
              .departure(T0.plusSeconds(30 * 60)));

      var risk = h.risk();
      assertThat(risk).hasSize(1);
      assertThat(risk.getFirst().payload().bagTag()).isEqualTo("IST-BAG");

      // The override boundary is strict as well: exactly 45 minutes is not HIGH.
      h.pipe(
          h.scan(Checkpoint.CHECK_IN, T0)
              .bag("IST-EDGE")
              .airport("IST")
              .departure(T0.plusSeconds(45 * 60)),
          h.scan(Checkpoint.CHECK_IN, T0)
              .bag("IST-INSIDE")
              .airport("IST")
              .departure(T0.plusSeconds(45 * 60 - 1)));
      var edge = h.risk();
      assertThat(edge).hasSize(1);
      assertThat(edge.getFirst().payload().bagTag()).isEqualTo("IST-INSIDE");
    }
  }

  @Test
  @DisplayName("a quiet bag still goes HIGH: the wall-clock punctuator re-evaluates the leg")
  void punctuatorRaisesHighWithoutNewScan() {
    // UNVERIFIED against case study
    try (TopologyHarness h = new TopologyHarness()) {
      h.pipe(h.scan(Checkpoint.CHECK_IN, T0).departure(at("10:25:00")));
      assertThat(h.risk()).isEmpty();

      // Exactly 20 minutes left: still not HIGH.
      h.advanceTo(at("10:05:00"));
      assertThat(h.risk()).isEmpty();

      // 19m30s left: HIGH, with no scan in between.
      h.advanceTo(at("10:05:30"));
      var risk = h.risk();
      assertThat(risk).hasSize(1);
      assertThat(risk.getFirst().key()).isEqualTo("BAG-1:LEG-1");
      assertThat(risk.getFirst().payload().previous()).isEqualTo(RiskLevel.LOW);
      assertThat(risk.getFirst().payload().current()).isEqualTo(RiskLevel.HIGH);

      // Emitted once: later ticks do not repeat it.
      h.advanceTo(at("10:08:00"));
      assertThat(h.risk()).isEmpty();
    }
  }

  @Test
  @DisplayName("the level is emitted only when it changes")
  void emitsOnlyOnChange() {
    // UNVERIFIED against case study
    try (TopologyHarness h = new TopologyHarness()) {
      h.pipe(
          h.scan(Checkpoint.CHECK_IN, at("10:00:00")).departure(at("10:15:00")),
          h.scan(Checkpoint.SORTING, at("10:01:00")).departure(at("10:15:00")),
          h.scan(Checkpoint.TRANSFER, at("10:02:00")).departure(at("10:15:00")));

      assertThat(h.risk()).hasSize(1);
    }
  }

  @Test
  @DisplayName("after departureAt the leg is evicted: no output, and it no longer counts as HIGH")
  void noOutputAfterDeparture() {
    // UNVERIFIED against case study
    try (TopologyHarness h = new TopologyHarness()) {
      h.pipe(
          h.scan(Checkpoint.CHECK_IN, T0).bag("HIGH-BAG").leg("L1").departure(at("10:15:00")),
          h.scan(Checkpoint.CHECK_IN, T0).bag("LOW-BAG").leg("L2").departure(at("10:50:00")));
      assertThat(h.risk()).hasSize(1);
      assertThat(h.gauge(RiskStreamsMetrics.BAGGAGE_RISK_HIGH)).isEqualTo(1.0);

      // Past the first departure: no LOW emitted on eviction.
      h.advanceTo(at("10:15:30"));
      assertThat(h.risk()).isEmpty();
      assertThat(h.gauge(RiskStreamsMetrics.BAGGAGE_RISK_HIGH)).isZero();

      // The second leg departs at 10:50 without ever having been HIGH: still nothing.
      h.advanceTo(at("10:50:30"));
      assertThat(h.risk()).isEmpty();
    }
  }

  @Test
  @DisplayName("legId null means no risk, even with the departure unknown and time passing")
  void legIdNullHasNoRisk() {
    // UNVERIFIED against case study
    try (TopologyHarness h = new TopologyHarness()) {
      h.pipe(h.scan(Checkpoint.CHECK_IN, T0).withoutLeg());
      h.advanceTo(at("11:00:00"));

      assertThat(h.risk()).isEmpty();
      assertThat(h.deadLetters()).isEmpty();
    }
  }

  @Test
  @DisplayName("a LOADED scan clears only its own leg")
  void loadedClearsOnlyItsLeg() {
    // UNVERIFIED against case study
    try (TopologyHarness h = new TopologyHarness()) {
      h.pipe(
          h.scan(Checkpoint.CHECK_IN, at("10:00:00")).leg("LEG-1").departure(at("10:15:00")),
          h.scan(Checkpoint.CHECK_IN, at("10:00:10")).leg("LEG-2").departure(at("10:16:00")));
      assertThat(h.risk()).hasSize(2);

      h.pipe(h.scan(Checkpoint.LOADED, at("10:01:00")).leg("LEG-1").departure(at("10:15:00")));

      var risk = h.risk();
      assertThat(risk).hasSize(1);
      assertThat(risk.getFirst().key()).isEqualTo("BAG-1:LEG-1");
      assertThat(risk.getFirst().payload().current()).isEqualTo(RiskLevel.LOW);
      assertThat(h.gauge(RiskStreamsMetrics.BAGGAGE_RISK_HIGH)).isEqualTo(1.0);
    }
  }

  @Test
  @DisplayName("a rejected LOADED scan is not a loading confirmation")
  void rejectedLoadedDoesNotClear() {
    // UNVERIFIED against case study
    try (TopologyHarness h = new TopologyHarness()) {
      h.pipe(
          h.scan(Checkpoint.ARRIVED, at("10:00:00")).departure(at("10:15:00")),
          // LOADED comes before ARRIVED and is newer: a rejected transition.
          h.scan(Checkpoint.LOADED, at("10:01:00")).departure(at("10:15:00")));

      var risk = h.risk();
      assertThat(risk).hasSize(1);
      assertThat(risk.getFirst().payload().current()).isEqualTo(RiskLevel.HIGH);
    }
  }

  @Test
  @DisplayName("output eventIds are deterministic across runs and differ per transition")
  void deterministicEventIds() {
    // UNVERIFIED against case study
    var firstRun = runHighThenLow();
    var secondRun = runHighThenLow();

    assertThat(firstRun).hasSize(2);
    assertThat(secondRun).containsExactlyElementsOf(firstRun);
    assertThat(firstRun.get(0)).isNotEqualTo(firstRun.get(1));
  }

  private java.util.List<String> runHighThenLow() {
    try (TopologyHarness h = new TopologyHarness()) {
      h.pipe(
          h.scan(Checkpoint.CHECK_IN, at("10:00:00")).departure(at("10:15:00")),
          h.scan(Checkpoint.LOADED, at("10:01:00")).departure(at("10:15:00")));
      return h.risk().stream().map(out -> out.envelope().eventId()).toList();
    }
  }

  @Test
  @DisplayName("a LOADED scan with a legId but no departureAt clears HIGH on the existing leg")
  void loadedWithoutDepartureClearsExistingLeg() {
    try (TopologyHarness h = new TopologyHarness()) {
      h.pipe(h.scan(Checkpoint.CHECK_IN, at("10:00:00")).departure(at("10:10:00")));
      assertThat(h.risk()).hasSize(1);

      h.pipe(h.scan(Checkpoint.LOADED, at("10:01:00")).departure(null));

      var risk = h.risk();
      assertThat(risk).hasSize(1);
      assertThat(risk.getFirst().payload().current()).isEqualTo(RiskLevel.LOW);
      assertThat(h.gauge(RiskStreamsMetrics.BAGGAGE_RISK_HIGH)).isZero();
    }
  }

  @Test
  @DisplayName("a scan with a legId but no departureAt and no existing leg is skipped")
  void noDepartureAndNoLegIsSkipped() {
    try (TopologyHarness h = new TopologyHarness()) {
      h.pipe(h.scan(Checkpoint.CHECK_IN, at("10:00:00")).departure(null));

      assertThat(h.risk()).isEmpty();
      assertThat(h.counter(RiskStreamsMetrics.SCANS_WITHOUT_LEG)).isEqualTo(1);
    }
  }
}
