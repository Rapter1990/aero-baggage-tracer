package com.aerotrace.riskstreams;

import static com.aerotrace.riskstreams.support.Fixtures.at;
import static org.assertj.core.api.Assertions.assertThat;

import com.aerotrace.common.model.AnomalyType;
import com.aerotrace.common.model.Checkpoint;
import com.aerotrace.common.model.RiskLevel;
import com.aerotrace.riskstreams.support.TopologyHarness;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

// UNVERIFIED against case study: AT-06, AT-07 and AT-08 are reconstructed from BR-02/BR-03.
@Tag("unverified-spec")
class AcceptanceScenariosTest {

  @Test
  @DisplayName("AT-06 HIGH risk near departure")
  void at06HighRiskNearDeparture() {
    // UNVERIFIED against case study
    try (TopologyHarness h = new TopologyHarness()) {
      // 15 minutes to departure, no loading confirmation yet: below the 20 minute threshold.
      h.pipe(h.scan(Checkpoint.CHECK_IN, at("10:00:00")).departure(at("10:15:00")));

      var risk = h.risk();
      assertThat(risk).hasSize(1);
      assertThat(risk.getFirst().key()).isEqualTo("BAG-1:LEG-1");
      assertThat(risk.getFirst().payload().bagTag()).isEqualTo("BAG-1");
      assertThat(risk.getFirst().payload().legId()).isEqualTo("LEG-1");
      assertThat(risk.getFirst().payload().previous()).isEqualTo(RiskLevel.LOW);
      assertThat(risk.getFirst().payload().current()).isEqualTo(RiskLevel.HIGH);
      assertThat(risk.getFirst().payload().departureAt()).isEqualTo(at("10:15:00"));
      assertThat(risk.getFirst().envelope().eventType()).isEqualTo("baggage.risk.changed");
      assertThat(risk.getFirst().envelope().schemaVersion()).isEqualTo(1);
      assertThat(risk.getFirst().envelope().aggregateId()).isEqualTo("BAG-1");
      assertThat(h.anomalies()).isEmpty();
      assertThat(h.deadLetters()).isEmpty();
    }
  }

  @Test
  @DisplayName("AT-07 scanner anomaly after 3 rejections in 5 min")
  void at07ScannerAnomalyAfterThreeRejections() {
    // UNVERIFIED against case study
    try (TopologyHarness h = new TopologyHarness()) {
      h.pipe(h.scan(Checkpoint.LOADED, at("10:00:00")));
      // Three backwards steps, each newer than the loading scan: rejected transitions.
      h.pipe(h.scan(Checkpoint.CHECK_IN, at("10:01:00")));
      h.pipe(h.scan(Checkpoint.SORTING, at("10:02:00")));
      assertThat(h.anomalies()).isEmpty();
      h.pipe(h.scan(Checkpoint.TRANSFER, at("10:03:00")));

      var anomalies = h.anomalies();
      assertThat(anomalies).hasSize(1);
      assertThat(anomalies.getFirst().key()).isEqualTo("BAG-1");
      assertThat(anomalies.getFirst().payload().bagTag()).isEqualTo("BAG-1");
      assertThat(anomalies.getFirst().payload().anomalyType())
          .isEqualTo(AnomalyType.SCANNER_ANOMALY);
      assertThat(anomalies.getFirst().payload().rejectedTransitions()).isEqualTo(3);
      assertThat(anomalies.getFirst().payload().windowStart()).isEqualTo(at("10:00:00"));
      assertThat(anomalies.getFirst().payload().windowEnd()).isEqualTo(at("10:05:00"));
      assertThat(anomalies.getFirst().envelope().eventType()).isEqualTo("baggage.scan.anomaly");
      assertThat(anomalies.getFirst().envelope().schemaVersion()).isEqualTo(1);
      assertThat(anomalies.getFirst().envelope().aggregateId()).isEqualTo("BAG-1");
    }
  }

  @Test
  @DisplayName("AT-08 loading confirmation clears HIGH")
  void at08LoadingConfirmationClearsHigh() {
    // UNVERIFIED against case study
    try (TopologyHarness h = new TopologyHarness()) {
      h.pipe(h.scan(Checkpoint.CHECK_IN, at("10:00:00")).departure(at("10:15:00")));
      h.pipe(h.scan(Checkpoint.LOADED, at("10:01:00")).departure(at("10:15:00")));

      var risk = h.risk();
      assertThat(risk).hasSize(2);
      assertThat(risk.get(0).payload().previous()).isEqualTo(RiskLevel.LOW);
      assertThat(risk.get(0).payload().current()).isEqualTo(RiskLevel.HIGH);
      assertThat(risk.get(1).payload().previous()).isEqualTo(RiskLevel.HIGH);
      assertThat(risk.get(1).payload().current()).isEqualTo(RiskLevel.LOW);

      // The confirmation is sticky: the leg does not turn HIGH again as departure nears.
      h.advanceTo(at("10:10:00"));
      assertThat(h.risk()).isEmpty();
    }
  }
}
