package com.aerotrace.riskstreams;

import static org.assertj.core.api.Assertions.assertThat;

import com.aerotrace.riskstreams.support.TopologyHarness;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Further poison shapes: each becomes exactly one dead letter and the stream goes on. */
class MalformedInputTest {

  @Test
  @DisplayName("valid JSON that is not an object is an invalid payload")
  void notAnObject() {
    try (TopologyHarness h = new TopologyHarness()) {
      h.pipeRaw("BAG-1", "[1,2]");

      var dead = h.deadLetters();
      assertThat(dead).hasSize(1);
      assertThat(dead.getFirst().payload().reason())
          .isEqualTo(RiskStreamsTopology.REASON_INVALID_PAYLOAD);
    }
  }

  @Test
  @DisplayName("an envelope that does not bind is an invalid payload, keyed by its eventId")
  void envelopeDoesNotBind() {
    try (TopologyHarness h = new TopologyHarness()) {
      h.pipeRaw("BAG-1", "{\"eventId\":\"e-1\",\"schemaVersion\":2,\"payload\":{}}");

      var dead = h.deadLetters();
      assertThat(dead).hasSize(1);
      assertThat(dead.getFirst().key()).isEqualTo("e-1");
      assertThat(dead.getFirst().payload().reason())
          .isEqualTo(RiskStreamsTopology.REASON_INVALID_PAYLOAD);
    }
  }

  @Test
  @DisplayName("a tombstone is dead-lettered, not a crash")
  void tombstone() {
    try (TopologyHarness h = new TopologyHarness()) {
      h.pipeRaw("BAG-1", null);

      assertThat(h.deadLetters()).hasSize(1);
    }
  }
}
