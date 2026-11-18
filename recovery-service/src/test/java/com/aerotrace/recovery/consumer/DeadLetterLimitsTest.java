package com.aerotrace.recovery.consumer;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DeadLetterLimitsTest {

  @Test
  @DisplayName("Poison a huge bad event is cut so its dead letter can still be sent")
  void hugeRawPayloadIsCut() {
    String huge = "x".repeat(KafkaConsumerConfiguration.MAX_RAW_PAYLOAD_CHARS * 3);

    assertThat(KafkaConsumerConfiguration.truncate(huge))
        .hasSize(KafkaConsumerConfiguration.MAX_RAW_PAYLOAD_CHARS);
  }

  @Test
  @DisplayName("Poison a small bad event is kept whole")
  void smallRawPayloadIsKept() {
    assertThat(KafkaConsumerConfiguration.truncate("{\"a\":1}")).isEqualTo("{\"a\":1}");
    assertThat(KafkaConsumerConfiguration.truncate(null)).isNull();
  }

  @Test
  @DisplayName("Poison an event id is capped before it becomes the key of a dead letter")
  void eventIdIsCapped() {
    assertThat(KafkaConsumerConfiguration.capped("e".repeat(500)))
        .hasSize(KafkaConsumerConfiguration.MAX_KEY_CHARS);
    assertThat(KafkaConsumerConfiguration.capped("EV-1")).isEqualTo("EV-1");
    assertThat(KafkaConsumerConfiguration.capped(null)).isNull();
  }
}
