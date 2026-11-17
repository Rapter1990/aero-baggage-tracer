package com.aerotrace.riskstreams;

import static com.aerotrace.riskstreams.support.Fixtures.at;
import static org.assertj.core.api.Assertions.assertThat;

import com.aerotrace.common.model.Checkpoint;
import com.aerotrace.riskstreams.support.TopologyHarness;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

class RiskStreamsMetricsTest {

  @Test
  @DisplayName("meter names are part of the contract")
  void meterNames() {
    assertThat(RiskStreamsMetrics.STREAM_LAG_SECONDS).isEqualTo("stream_lag_seconds");
    assertThat(RiskStreamsMetrics.BAGGAGE_RISK_HIGH).isEqualTo("baggage_risk_high");
  }

  @Test
  @DisplayName("stream_lag_seconds is wall-clock now minus the event time of the last scan")
  void streamLag() {
    try (TopologyHarness h = new TopologyHarness()) {
      assertThat(h.gauge("stream_lag_seconds")).isZero();

      h.advanceTo(at("10:10:00"));
      h.pipe(h.scan(Checkpoint.CHECK_IN, at("10:08:00")));

      assertThat(h.gauge("stream_lag_seconds")).isEqualTo(120.0);
    }
  }

  @Test
  @DisplayName("baggage_risk_high counts the HIGH legs")
  @Tag("unverified-spec")
  void riskHighGauge() {
    // UNVERIFIED against case study
    try (TopologyHarness h = new TopologyHarness()) {
      assertThat(h.gauge("baggage_risk_high")).isZero();

      h.pipe(
          h.scan(Checkpoint.CHECK_IN, at("10:00:00")).leg("L1").departure(at("10:15:00")),
          h.scan(Checkpoint.CHECK_IN, at("10:00:00")).leg("L2").departure(at("10:16:00")));
      assertThat(h.gauge("baggage_risk_high")).isEqualTo(2.0);

      h.pipe(h.scan(Checkpoint.LOADED, at("10:01:00")).leg("L1").departure(at("10:15:00")));
      assertThat(h.gauge("baggage_risk_high")).isEqualTo(1.0);
    }
  }

  @Test
  @DisplayName("scans without a leg are counted")
  @Tag("unverified-spec")
  void scansWithoutLeg() {
    // UNVERIFIED against case study
    try (TopologyHarness h = new TopologyHarness()) {
      h.pipe(
          h.scan(Checkpoint.CHECK_IN, at("10:00:00")).withoutLeg(),
          h.scan(Checkpoint.SORTING, at("10:00:10")).withoutLeg(),
          h.scan(Checkpoint.SORTING, at("10:00:20")));

      assertThat(h.counter(RiskStreamsMetrics.SCANS_WITHOUT_LEG)).isEqualTo(2.0);
    }
  }
}
