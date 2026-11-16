package com.aerotrace.common.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CheckpointTest {

  @Test
  void theCheckpointsAreInTheOrderOfANormalTrip() {
    assertThat(Checkpoint.values())
        .containsExactly(
            Checkpoint.CHECK_IN,
            Checkpoint.SORTING,
            Checkpoint.TRANSFER,
            Checkpoint.LOADED,
            Checkpoint.ARRIVED,
            Checkpoint.CLAIM);
  }

  @Test
  void parsingIgnoresCaseAndSurroundingSpaceAndRejectsUnknownValues() {
    assertThat(Checkpoint.parse("loaded")).contains(Checkpoint.LOADED);
    assertThat(Checkpoint.parse(" claim ")).contains(Checkpoint.CLAIM);
    assertThat(Checkpoint.parse("nowhere")).isEmpty();
    assertThat(Checkpoint.parse("")).isEmpty();
    assertThat(Checkpoint.parse(null)).isEmpty();
  }
}
