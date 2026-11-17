package com.aerotrace.riskstreams;

import static com.aerotrace.riskstreams.support.Fixtures.at;
import static org.assertj.core.api.Assertions.assertThat;

import com.aerotrace.common.model.Checkpoint;
import com.aerotrace.riskstreams.support.ScanBuilder;
import com.aerotrace.riskstreams.support.TopologyHarness;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

class ScanDeduplicationTest {

  @Test
  @DisplayName("a redelivered scan produces no output and no state change")
  @Tag("unverified-spec")
  void duplicateHasNoEffect() {
    // UNVERIFIED against case study (relies on BR-02 for the risk outputs)
    try (TopologyHarness h = new TopologyHarness()) {
      ScanBuilder high = h.scan(Checkpoint.CHECK_IN, at("10:00:00")).departure(at("10:15:00"));
      ScanBuilder loaded = h.scan(Checkpoint.LOADED, at("10:01:00")).departure(at("10:15:00"));
      h.pipe(high, loaded);
      assertThat(h.risk()).hasSize(2);

      // Redeliver the CHECK_IN: were it applied, the leg would not change anyway, so also
      // redeliver the LOADED and check nothing is emitted and the leg stays cleared.
      h.pipe(high.redelivery(), loaded.redelivery());
      assertThat(h.risk()).isEmpty();
      assertThat(h.anomalies()).isEmpty();
      assertThat(h.deadLetters()).isEmpty();
      assertThat(h.gauge(RiskStreamsMetrics.BAGGAGE_RISK_HIGH)).isZero();
    }
  }

  @Test
  @DisplayName("a duplicate does not re-apply a state change after later scans")
  @Tag("unverified-spec")
  void duplicateDoesNotRollBackState() {
    // UNVERIFIED against case study (relies on BR-02)
    try (TopologyHarness h = new TopologyHarness()) {
      ScanBuilder first = h.scan(Checkpoint.CHECK_IN, at("10:00:00")).departure(at("10:15:00"));
      h.pipe(first);
      h.pipe(h.scan(Checkpoint.LOADED, at("10:01:00")).departure(at("10:15:00")));
      assertThat(h.risk()).hasSize(2);

      // Were the old CHECK_IN applied again it could flip the leg back; it must not.
      h.pipe(first.redelivery());

      assertThat(h.risk()).isEmpty();
      assertThat(h.gauge(RiskStreamsMetrics.BAGGAGE_RISK_HIGH)).isZero();
    }
  }

  @Test
  @DisplayName("dedup is keyed on scanEventId, not on the envelope eventId")
  void keyedOnScanEventId() {
    try (TopologyHarness h = new TopologyHarness()) {
      ScanBuilder first = h.scan(Checkpoint.CHECK_IN, at("10:00:00")).departure(at("10:15:00"));
      // Same scanEventId, different envelope eventId: still a duplicate.
      h.pipe(first, first.redelivery());

      assertThat(h.risk()).hasSize(1);
    }
  }
}
