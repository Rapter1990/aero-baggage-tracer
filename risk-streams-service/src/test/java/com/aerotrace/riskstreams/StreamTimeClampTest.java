package com.aerotrace.riskstreams;

import static com.aerotrace.riskstreams.support.Fixtures.at;
import static org.assertj.core.api.Assertions.assertThat;

import com.aerotrace.common.model.Checkpoint;
import com.aerotrace.riskstreams.support.TopologyHarness;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** A scan dated in the future must not drag stream time forward (max clock skew). */
class StreamTimeClampTest {

  @Test
  @DisplayName("a far-future scan does not make later on-time scans late or hide the anomaly")
  void futureScanDoesNotAdvanceStreamTime() {
    try (TopologyHarness h = new TopologyHarness()) {
      // A bag whose device clock is a month ahead.
      h.pipe(
          h.scan(Checkpoint.CHECK_IN, at("10:00:00").plus(Duration.ofDays(30))).bag("BAG-FUTURE"));

      h.pipe(h.scan(Checkpoint.LOADED, at("10:00:00")));
      h.pipe(
          h.scan(Checkpoint.CHECK_IN, at("10:01:00")),
          h.scan(Checkpoint.CHECK_IN, at("10:02:00")),
          h.scan(Checkpoint.CHECK_IN, at("10:03:00")));

      assertThat(h.counter(RiskStreamsMetrics.LATE_RECORDS)).isZero();
      assertThat(h.anomalies()).hasSize(1);
    }
  }
}
