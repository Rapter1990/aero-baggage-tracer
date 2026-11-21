package com.aerotrace.notification.consumer;

import static org.assertj.core.api.Assertions.assertThat;

import com.aerotrace.common.event.EventJson;
import com.aerotrace.common.model.RecoveryState;
import com.aerotrace.notification.base.CaseEvents;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * The raw copy of a bad event that goes onto the dead-letter topic (ADR 0005, decision 7): enough
 * for an operator to see and replay the event, never the case's free-text {@code reason}.
 */
class DeadLetterRawPayloadTest {

  private static final String REASON = "Maria Gonzalez (passport P1234567) is at gate B12";

  @Test
  @DisplayName("BR-08 the raw copy of a readable event keeps the event but drops payload.reason")
  void readableEventLosesItsReason() {
    String eventId = CaseEvents.eventId();
    String caseId = "not-a-uuid";
    String json =
        CaseEvents.event(
            eventId, caseId, CaseEvents.bagTag(), RecoveryState.OPEN, 1, REASON, "handler-x");

    String raw = KafkaConsumerConfiguration.rawCopy(json);

    assertThat(raw).doesNotContain("Maria", "P1234567", "gate B12").doesNotContain("\"reason\"");
    JsonNode tree = EventJson.mapper().readTree(raw);
    assertThat(tree.get("eventId").asString()).isEqualTo(eventId);
    assertThat(tree.get("payload").get("caseId").asString()).isEqualTo(caseId);
    assertThat(tree.get("payload").has("reason")).isFalse();
    assertThat(tree.get("payload").get("assignedTo").asString()).isEqualTo("handler-x");
  }

  @Test
  @DisplayName("BR-08 a reason field anywhere in a readable event is dropped, not only in payload")
  void reasonIsDroppedAtAnyDepth() {
    String raw =
        KafkaConsumerConfiguration.rawCopy(
            "{\"eventId\":\"EV-1\",\"reason\":\"secret-a\","
                + "\"payload\":{\"nested\":{\"reason\":\"secret-b\"},\"list\":[{\"reason\":\"c\"}]}}");

    assertThat(raw).doesNotContain("secret-a", "secret-b", "\"reason\"").contains("EV-1");
  }

  @Test
  @DisplayName("BR-08 unreadable text that mentions a reason is not copied at all")
  void unreadableTextWithAReasonIsWithheld() {
    String truncated = "{\"eventId\":\"EV-1\",\"payload\":{\"reason\":\"" + REASON;

    assertThat(KafkaConsumerConfiguration.rawCopy(truncated)).isNull();
    assertThat(KafkaConsumerConfiguration.rawCopy("garbage REASON: " + REASON)).isNull();
  }

  @Test
  @DisplayName("BR-08 unreadable text without a reason is copied, cut to the size limit")
  void unreadableTextWithoutAReasonIsKept() {
    assertThat(KafkaConsumerConfiguration.rawCopy("garbage {")).isEqualTo("garbage {");
    String huge = "x".repeat(KafkaConsumerConfiguration.MAX_RAW_PAYLOAD_CHARS + 10);
    assertThat(KafkaConsumerConfiguration.rawCopy(huge))
        .hasSize(KafkaConsumerConfiguration.MAX_RAW_PAYLOAD_CHARS);
    assertThat(KafkaConsumerConfiguration.rawCopy(null)).isNull();
  }
}
