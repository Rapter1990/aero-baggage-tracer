package com.aerotrace.riskstreams;

import static com.aerotrace.riskstreams.support.Fixtures.at;
import static org.assertj.core.api.Assertions.assertThat;

import com.aerotrace.common.event.Topics;
import com.aerotrace.common.model.Checkpoint;
import com.aerotrace.riskstreams.support.TopologyHarness;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RiskStreamsTopologyShapeTest {

  @Test
  @DisplayName("reads the scan topic, writes risk, anomaly and dead-letter topics")
  void topics() {
    try (TopologyHarness h = new TopologyHarness()) {
      String description = h.topology().describe().toString();

      assertThat(description)
          .contains(Topics.SCAN_RECEIVED, Topics.RISK_CHANGED, Topics.SCAN_ANOMALY)
          .contains(Topics.DEAD_LETTER);
    }
  }

  @Test
  @DisplayName("the dedup store is durable: it writes to a changelog topic")
  void dedupStoreHasChangelog() {
    try (TopologyHarness h = new TopologyHarness()) {
      h.pipe(h.scan(Checkpoint.CHECK_IN, at("10:00:00")));

      assertThat(h.driver().producedTopicNames())
          .contains(
              TopologyHarness.APPLICATION_ID
                  + "-"
                  + RiskStreamsTopology.DEDUP_STORE
                  + "-changelog");
    }
  }
}
