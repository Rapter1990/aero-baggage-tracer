package com.aerotrace.riskstreams;

import static com.aerotrace.riskstreams.support.Fixtures.at;
import static org.assertj.core.api.Assertions.assertThat;

import com.aerotrace.common.model.Checkpoint;
import com.aerotrace.riskstreams.support.ScanBuilder;
import com.aerotrace.riskstreams.support.TopologyHarness;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** A record that parses but fails inside the processor is dead-lettered, not retried forever. */
class ProcessingErrorTest {

  @Test
  @DisplayName("an unexpected failure dead-letters the record and the next one still processes")
  void failureIsDeadLetteredAndStreamContinues() {
    try (TopologyHarness h = new TopologyHarness()) {
      // Parses, but Instant.MAX does not fit epoch milliseconds: the processor fails on it.
      ScanBuilder poison = h.scan(Checkpoint.CHECK_IN, Instant.MAX);
      h.pipe(poison);

      var dead = h.deadLetters();
      assertThat(dead).hasSize(1);
      assertThat(dead.getFirst().payload().reason()).isEqualTo("PROCESSING_ERROR");
      assertThat(dead.getFirst().payload().attempts()).isEqualTo(1);
      assertThat(dead.getFirst().payload().originalEventId()).isEqualTo(poison.eventId());
      assertThat(dead.getFirst().payload().rawPayload()).isEqualTo(poison.json());
      assertThat(h.counter("risk_streams_processing_errors")).isEqualTo(1);

      // The next record is processed normally (HIGH: 10 minutes to departure).
      h.pipe(h.scan(Checkpoint.CHECK_IN, at("10:00:00")).departure(at("10:10:00")));
      assertThat(h.risk()).hasSize(1);
      assertThat(h.deadLetters()).isEmpty();
    }
  }

  @Test
  @DisplayName("a failed record leaves no half-applied state: its redelivery fails the same way")
  void failedRecordLeavesNoDedupMark() {
    try (TopologyHarness h = new TopologyHarness()) {
      ScanBuilder poison = h.scan(Checkpoint.CHECK_IN, Instant.MAX);
      h.pipe(poison, poison.redelivery());

      // Were the dedup mark committed by the first failure, the second would vanish silently.
      assertThat(h.deadLetters()).hasSize(2);
      assertThat(h.counter("risk_streams_processing_errors")).isEqualTo(2);
    }
  }
}
