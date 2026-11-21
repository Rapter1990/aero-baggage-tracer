package com.aerotrace.notification.delivery;

import static org.assertj.core.api.Assertions.assertThat;

import com.aerotrace.common.event.DeadLetterPayload;
import com.aerotrace.common.event.EventEnvelope;
import com.aerotrace.common.event.EventJson;
import com.aerotrace.common.event.Topics;
import com.aerotrace.notification.base.AbstractNotificationTest;
import com.aerotrace.notification.base.CaseEvents;
import com.aerotrace.notification.base.KafkaFixture;
import com.aerotrace.notification.model.entity.NotificationStatus;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** The dead letter of an exhausted delivery on a real broker: it arrives, and only once. */
@SpringBootTest
class DeliveryExhaustionKafkaTest extends AbstractNotificationTest {

  private static KafkaFixture kafka;

  private static synchronized KafkaFixture kafka() {
    if (kafka == null) {
      kafka = KafkaFixture.shared();
    }
    return kafka;
  }

  @DynamicPropertySource
  static void kafkaProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.kafka.bootstrap-servers", () -> kafka().bootstrapServers());
    registry.add("spring.kafka.producer.properties.max.block.ms", () -> "10000");
    registry.add("spring.kafka.producer.properties.request.timeout.ms", () -> "10000");
    registry.add("spring.kafka.producer.properties.delivery.timeout.ms", () -> "20000");
  }

  @Autowired DeliveryDispatcher dispatcher;

  @Test
  @DisplayName(
      "BR-08 retry exhaustion marks the row FAILED and dead-letters it once to baggage.dead-letter.v1")
  void exhaustionIsDeadLetteredOnce() {
    String eventId = CaseEvents.eventId();
    UUID id = seed("handler-a", eventId, "CASE_OPENED", clock.instant());
    provider.failAll();

    for (int attempt = 1; attempt <= 5; attempt++) {
      dispatcher.dispatchDue();
      clock.advance(Duration.ofMinutes(10));
    }
    // More runs, as the scheduler would make: they must publish nothing more.
    for (int i = 0; i < 3; i++) {
      clock.advance(Duration.ofMinutes(10));
      dispatcher.dispatchDue();
    }

    assertThat(row(id).getStatus()).isEqualTo(NotificationStatus.FAILED);
    assertThat(row(id).getDeadLetteredAt()).isNotNull();
    List<ConsumerRecord<String, String>> dead =
        kafka()
            .readByKey(
                Topics.DEAD_LETTER, eventId, 1, Duration.ofSeconds(4), Duration.ofSeconds(60));
    assertThat(dead).hasSize(1);
    EventEnvelope<DeadLetterPayload> envelope =
        EventJson.read(dead.getFirst().value(), DeadLetterPayload.class);
    assertThat(envelope.payload().originalTopic()).isEqualTo(Topics.CASE_CHANGED);
    assertThat(envelope.payload().originalEventId()).isEqualTo(eventId);
    assertThat(envelope.payload().reason()).isEqualTo("DELIVERY_EXHAUSTED");
    assertThat(envelope.payload().attempts()).isEqualTo(5);
    assertThat(envelope.payload().rawPayload()).isNull();
  }
}
