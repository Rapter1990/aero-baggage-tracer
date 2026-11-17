package com.aerotrace.riskstreams;

import static com.aerotrace.riskstreams.support.Fixtures.at;
import static org.assertj.core.api.Assertions.assertThat;

import com.aerotrace.common.model.Checkpoint;
import com.aerotrace.riskstreams.support.ScanBuilder;
import com.aerotrace.riskstreams.support.TopologyHarness;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

// UNVERIFIED against case study: the whole class encodes BR-03 as reconstructed by the architect.
// Every test starts from a LOADED scan at 10:00:00 (the latest legitimate checkpoint), so a scan
// of an earlier checkpoint that is newer than 10:00:00 is a rejected transition. The departure is
// four hours away, so no risk output interferes.
@Tag("unverified-spec")
class ScanAnomalyTest {

  private static ScanBuilder rejected(TopologyHarness h, String time) {
    return h.scan(Checkpoint.CHECK_IN, at(time));
  }

  @Test
  @DisplayName("two rejected transitions are not an anomaly")
  void twoRejectionsAreNotEnough() {
    // UNVERIFIED against case study
    try (TopologyHarness h = new TopologyHarness()) {
      h.pipe(h.scan(Checkpoint.LOADED, at("10:00:00")));
      h.pipe(rejected(h, "10:01:00"), rejected(h, "10:02:00"));

      assertThat(h.anomalies()).isEmpty();
    }
  }

  @Test
  @DisplayName("an event exactly at windowEnd belongs to the next window")
  void windowEndIsExclusive() {
    // UNVERIFIED against case study
    try (TopologyHarness h = new TopologyHarness()) {
      h.pipe(h.scan(Checkpoint.LOADED, at("10:00:00")));
      // Two in [10:00,10:05) and one exactly at 10:05:00, which is in [10:05,10:10).
      h.pipe(rejected(h, "10:04:00"), rejected(h, "10:04:30"), rejected(h, "10:05:00"));
      assertThat(h.anomalies()).isEmpty();

      // Two more in the second window make three there.
      h.pipe(rejected(h, "10:05:30"));
      assertThat(h.anomalies()).isEmpty();
      h.pipe(rejected(h, "10:06:00"));

      var anomalies = h.anomalies();
      assertThat(anomalies).hasSize(1);
      assertThat(anomalies.getFirst().payload().windowStart()).isEqualTo(at("10:05:00"));
      assertThat(anomalies.getFirst().payload().windowEnd()).isEqualTo(at("10:10:00"));
      assertThat(anomalies.getFirst().payload().rejectedTransitions()).isEqualTo(3);
    }
  }

  @Test
  @DisplayName("an event exactly at windowStart belongs to that window")
  void windowStartIsInclusive() {
    // UNVERIFIED against case study
    try (TopologyHarness h = new TopologyHarness()) {
      h.pipe(h.scan(Checkpoint.LOADED, at("10:00:00")));
      h.pipe(rejected(h, "10:05:00"), rejected(h, "10:07:00"), rejected(h, "10:09:59"));

      var anomalies = h.anomalies();
      assertThat(anomalies).hasSize(1);
      assertThat(anomalies.getFirst().payload().windowStart()).isEqualTo(at("10:05:00"));
    }
  }

  @Test
  @DisplayName(
      "one anomaly per window, however many rejections follow; the next window can raise another")
  void emitsOncePerWindow() {
    // UNVERIFIED against case study
    try (TopologyHarness h = new TopologyHarness()) {
      h.pipe(h.scan(Checkpoint.LOADED, at("10:00:00")));
      h.pipe(
          rejected(h, "10:01:00"),
          rejected(h, "10:01:10"),
          rejected(h, "10:01:20"),
          rejected(h, "10:01:30"),
          rejected(h, "10:01:40"),
          rejected(h, "10:01:50"));
      assertThat(h.anomalies()).hasSize(1);

      h.pipe(rejected(h, "10:06:00"), rejected(h, "10:06:10"), rejected(h, "10:06:20"));
      var second = h.anomalies();
      assertThat(second).hasSize(1);
      assertThat(second.getFirst().payload().windowStart()).isEqualTo(at("10:05:00"));
    }
  }

  @Test
  @DisplayName("scans older than the latest checkpoint by event time are history, not rejections")
  void outOfOrderIsNotARejection() {
    // UNVERIFIED against case study
    try (TopologyHarness h = new TopologyHarness()) {
      h.pipe(h.scan(Checkpoint.LOADED, at("10:03:00")));
      // Arrive late but happened before the LOADED scan: earlier checkpoints, older time.
      h.pipe(
          h.scan(Checkpoint.CHECK_IN, at("10:00:30")),
          h.scan(Checkpoint.SORTING, at("10:01:00")),
          h.scan(Checkpoint.TRANSFER, at("10:02:00")));

      assertThat(h.anomalies()).isEmpty();

      // Control: the same backwards steps, but newer than the latest scan, do raise it.
      h.pipe(rejected(h, "10:03:30"), rejected(h, "10:03:40"), rejected(h, "10:03:50"));
      assertThat(h.anomalies()).hasSize(1);
    }
  }

  @Test
  @DisplayName("repeating the same checkpoint is allowed")
  void sameCheckpointRepeatIsNotARejection() {
    // UNVERIFIED against case study
    try (TopologyHarness h = new TopologyHarness()) {
      h.pipe(h.scan(Checkpoint.LOADED, at("10:00:00")));
      h.pipe(
          h.scan(Checkpoint.LOADED, at("10:01:00")),
          h.scan(Checkpoint.LOADED, at("10:02:00")),
          h.scan(Checkpoint.LOADED, at("10:03:00")),
          h.scan(Checkpoint.LOADED, at("10:04:00")));
      assertThat(h.anomalies()).isEmpty();

      // Control: three real rejections still count afterwards.
      h.pipe(rejected(h, "10:04:10"), rejected(h, "10:04:20"), rejected(h, "10:04:30"));
      assertThat(h.anomalies()).hasSize(1);
    }
  }

  @Test
  @DisplayName("a duplicate scan (same scanEventId) is counted once")
  void duplicateScanIsCountedOnce() {
    // UNVERIFIED against case study
    try (TopologyHarness h = new TopologyHarness()) {
      h.pipe(h.scan(Checkpoint.LOADED, at("10:00:00")));
      ScanBuilder first = rejected(h, "10:01:00");
      h.pipe(first, first.redelivery(), first.redelivery());
      assertThat(h.anomalies()).isEmpty();

      // Two more distinct scans make three distinct rejections, not five.
      h.pipe(rejected(h, "10:02:00"));
      assertThat(h.anomalies()).isEmpty();
      h.pipe(rejected(h, "10:03:00"));
      var anomalies = h.anomalies();
      assertThat(anomalies).hasSize(1);
      assertThat(anomalies.getFirst().payload().rejectedTransitions()).isEqualTo(3);
    }
  }

  @Test
  @DisplayName("rejections on different legs of one bag are counted together per bagTag")
  void rejectionsCountPerBagAcrossLegs() {
    // UNVERIFIED against case study
    try (TopologyHarness h = new TopologyHarness()) {
      h.pipe(
          h.scan(Checkpoint.LOADED, at("10:00:00")).leg("LEG-1"),
          h.scan(Checkpoint.LOADED, at("10:00:05")).leg("LEG-2"));
      h.pipe(
          rejected(h, "10:01:00").leg("LEG-1"),
          rejected(h, "10:02:00").leg("LEG-1"),
          rejected(h, "10:03:00").leg("LEG-2"));

      assertThat(h.anomalies()).hasSize(1);
    }
  }

  @Test
  @DisplayName("rejections of different bags are not mixed")
  void rejectionsAreSeparatePerBag() {
    // UNVERIFIED against case study
    try (TopologyHarness h = new TopologyHarness()) {
      h.pipe(
          h.scan(Checkpoint.LOADED, at("10:00:00")).bag("BAG-A"),
          h.scan(Checkpoint.LOADED, at("10:00:00")).bag("BAG-B"));
      h.pipe(
          rejected(h, "10:01:00").bag("BAG-A"),
          rejected(h, "10:02:00").bag("BAG-B"),
          rejected(h, "10:03:00").bag("BAG-A"),
          rejected(h, "10:04:00").bag("BAG-B"));

      assertThat(h.anomalies()).isEmpty();
    }
  }

  @Test
  @DisplayName("legId null scans update no leg state and never count as rejections")
  void legIdNullDoesNotCountRejections() {
    // Deliberate change (review finding): leg-less scans used to share one bag-level pseudo-leg
    // and so could look like rejected transitions. Without a leg there is nothing to compare.
    try (TopologyHarness h = new TopologyHarness()) {
      h.pipe(h.scan(Checkpoint.LOADED, at("10:00:00")).withoutLeg());
      h.pipe(
          rejected(h, "10:01:00").withoutLeg(),
          rejected(h, "10:02:00").withoutLeg(),
          rejected(h, "10:03:00").withoutLeg());

      assertThat(h.anomalies()).isEmpty();
      assertThat(h.risk()).isEmpty();
      assertThat(h.counter(RiskStreamsMetrics.SCANS_WITHOUT_LEG)).isEqualTo(4);
    }
  }

  @Test
  @DisplayName("a legitimate multi-leg connection without legId raises no anomaly")
  void legitimateConnectionWithoutLegIsNotAnAnomaly() {
    try (TopologyHarness h = new TopologyHarness()) {
      // Arrive (ARRIVED), then check in for the next flight (CHECK_IN), three times in 5 minutes.
      h.pipe(
          h.scan(Checkpoint.ARRIVED, at("10:00:00")).withoutLeg(),
          h.scan(Checkpoint.CHECK_IN, at("10:00:30")).withoutLeg(),
          h.scan(Checkpoint.ARRIVED, at("10:01:00")).withoutLeg(),
          h.scan(Checkpoint.CHECK_IN, at("10:01:30")).withoutLeg(),
          h.scan(Checkpoint.CLAIM, at("10:02:00")).withoutLeg(),
          h.scan(Checkpoint.TRANSFER, at("10:02:30")).withoutLeg());

      assertThat(h.anomalies()).isEmpty();
    }
  }
}
