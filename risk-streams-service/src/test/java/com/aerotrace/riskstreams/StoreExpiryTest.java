package com.aerotrace.riskstreams;

import static com.aerotrace.riskstreams.support.Fixtures.at;
import static org.assertj.core.api.Assertions.assertThat;

import com.aerotrace.common.model.Checkpoint;
import com.aerotrace.riskstreams.support.ScanBuilder;
import com.aerotrace.riskstreams.support.TopologyHarness;
import java.time.Duration;
import java.time.Instant;
import org.apache.kafka.streams.state.KeyValueStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Store expiry must not depend on the Kafka record timestamp or on the highest timestamp a store
 * has seen: a replayed or delayed backlog has an occurredAt far older than the (fresh) record
 * timestamp.
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
  @DisplayName("a duplicate of a scan older than the retention is still dropped after live traffic")
  void duplicateOfReplayedOldScanIsDropped() {
    try (TopologyHarness h = new TopologyHarness()) {
      liveTraffic(h);
      ScanBuilder replayed = h.scan(Checkpoint.CHECK_IN, old("10:01:00")).withoutLeg();

      h.pipe(replayed, replayed.redelivery(), replayed.redelivery());

      // Counted once: the scan is processed once, then recognised as a duplicate.
      assertThat(h.counter(RiskStreamsMetrics.SCANS_WITHOUT_LEG)).isEqualTo(1);
    }
  }

  @Test
  @DisplayName("a replayed backlog days older than its record timestamps raises one anomaly")
  void replayedBacklogRaisesTheAnomalyOnce() {
    try (TopologyHarness h = new TopologyHarness()) {
      liveTraffic(h);
      // A restart or rebalance: stream time starts over, the restored stores do not.
      h.restart();

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
  @DisplayName("deduplication survives a restart on the same state directory")
  void dedupSurvivesRestart() {
    try (TopologyHarness h = new TopologyHarness()) {
      ScanBuilder first = h.scan(Checkpoint.CHECK_IN, at("10:00:00")).withoutLeg();
      h.pipe(first);
      assertThat(h.counter(RiskStreamsMetrics.SCANS_WITHOUT_LEG)).isEqualTo(1);

      h.restart();
      h.pipe(first.redelivery(), h.scan(Checkpoint.CHECK_IN, at("10:00:30")).withoutLeg());

      // The new driver has new meters on the same registry: one more scan counted, not two.
      assertThat(h.counter(RiskStreamsMetrics.SCANS_WITHOUT_LEG)).isEqualTo(2);
    }
  }

  @Test
  @DisplayName("dedup entries and anomaly counts are deleted by the wall-clock punctuator")
  void expiredEntriesAreDeleted() {
    try (TopologyHarness h = new TopologyHarness()) {
      h.pipe(h.scan(Checkpoint.LOADED, at("10:00:00")));
      h.pipe(
          h.scan(Checkpoint.CHECK_IN, at("10:01:00")),
          h.scan(Checkpoint.CHECK_IN, at("10:02:00")),
          h.scan(Checkpoint.CHECK_IN, at("10:03:00")));
      KeyValueStore<String, Long> dedup =
          h.driver().getKeyValueStore(RiskStreamsTopology.DEDUP_STORE);
      assertThat(dedup.approximateNumEntries()).isPositive();

      // 20 minutes: past window + grace for the anomaly counts, far from the dedup retention.
      h.advanceTo(at("10:20:00"));
      assertThat(h.driver().getKeyValueStore(RiskStreamsTopology.ANOMALY_STORE).all().hasNext())
          .isFalse();
      assertThat(dedup.get("id|scan-2")).isNotNull();

      h.advanceTo(at("10:00:00").plus(Duration.ofDays(8)));
      assertThat(dedup.get("id|scan-2")).isNull();
      assertThat(dedup.all().hasNext()).isFalse();
    }
  }
}
