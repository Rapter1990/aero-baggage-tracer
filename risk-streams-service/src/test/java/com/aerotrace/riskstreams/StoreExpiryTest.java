package com.aerotrace.riskstreams;

import static com.aerotrace.riskstreams.support.Fixtures.at;
import static org.assertj.core.api.Assertions.assertThat;

import com.aerotrace.common.model.Checkpoint;
import com.aerotrace.riskstreams.support.ScanBuilder;
import com.aerotrace.riskstreams.support.TopologyHarness;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Store expiry must not depend on the Kafka record timestamp: a replayed or delayed backlog has an
 * occurredAt far older than the (fresh) record timestamp.
 */
class StoreExpiryTest {

  private static final Duration TEN_DAYS = Duration.ofDays(10);

  private static Instant old(String time) {
    return at(time).minus(TEN_DAYS);
  }

  /** Live traffic first: it moves the stores' observed time to "now" before the backlog arrives. */
  private static void liveTraffic(TopologyHarness h) {
    h.pipe(h.scan(Checkpoint.LOADED, at("10:00:00")).bag("BAG-LIVE"));
    h.pipe(h.scan(Checkpoint.CHECK_IN, at("10:00:10")).bag("BAG-LIVE"));
  }

  @Test
  @DisplayName("replayed scans days older than their record timestamp still raise one anomaly")
  void replayedBacklogStillRaisesTheAnomalyOnce() {
    try (TopologyHarness h = new TopologyHarness()) {
      liveTraffic(h);
      h.pipe(h.scan(Checkpoint.LOADED, old("10:00:00")));
      h.pipe(
          h.scan(Checkpoint.CHECK_IN, old("10:01:00")),
          h.scan(Checkpoint.CHECK_IN, old("10:02:00")),
          h.scan(Checkpoint.CHECK_IN, old("10:03:00")),
          h.scan(Checkpoint.CHECK_IN, old("10:03:30")),
          h.scan(Checkpoint.CHECK_IN, old("10:04:00")));

      var anomalies = h.anomalies();
      assertThat(anomalies).hasSize(1);
      assertThat(anomalies.getFirst().payload().windowStart()).isEqualTo(old("10:00:00"));
      assertThat(anomalies.getFirst().payload().rejectedTransitions()).isEqualTo(3);
    }
  }

  @Test
  @DisplayName("a duplicate of a replayed old scan is still dropped")
  void duplicateOfReplayedScanIsDropped() {
    try (TopologyHarness h = new TopologyHarness()) {
      liveTraffic(h);
      h.pipe(h.scan(Checkpoint.LOADED, old("10:00:00")));
      ScanBuilder first = h.scan(Checkpoint.CHECK_IN, old("10:01:00"));
      h.pipe(first, first.redelivery(), first.redelivery());
      h.pipe(h.scan(Checkpoint.CHECK_IN, old("10:02:00")));

      // Two distinct rejections: a third delivery of the first one must not make three.
      assertThat(h.anomalies()).isEmpty();
    }
  }
}
