package com.aerotrace.search.indexer;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The correlation id a dead letter carries: the original one when it is safe, otherwise none. */
class DeadLetterCorrelationTest {

  @Test
  @DisplayName("a dead letter keeps the correlation id of the event it replaces")
  void safeIdIsKept() {
    assertThat(
            DeadLetterPublisher.correlationIdOf(
                "{\"eventId\":\"e-1\",\"correlationId\":\"corr-1\"}"))
        .isEqualTo("corr-1");
  }

  @Test
  @DisplayName("an unsafe, missing or unreadable correlation id is dropped, never invented")
  void unsafeIdIsDropped() {
    assertThat(DeadLetterPublisher.correlationIdOf("{\"correlationId\":\"a b\nc\"}")).isNull();
    assertThat(
            DeadLetterPublisher.correlationIdOf("{\"correlationId\":\"" + "x".repeat(300) + "\"}"))
        .isNull();
    assertThat(DeadLetterPublisher.correlationIdOf("{\"eventId\":\"e-1\"}")).isNull();
    assertThat(DeadLetterPublisher.correlationIdOf("{not json")).isNull();
    assertThat(DeadLetterPublisher.correlationIdOf(null)).isNull();
  }
}
