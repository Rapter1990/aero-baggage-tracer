package com.aerotrace.notification.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.aerotrace.common.event.Topics;
import com.aerotrace.notification.base.AbstractNotificationTest;
import com.aerotrace.notification.base.CaseEvents;
import com.aerotrace.notification.base.KafkaFixture;
import com.aerotrace.notification.model.entity.NotificationStatus;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Kafka becomes unreachable while a notification is dead-lettered. The row is FAILED and stays
 * undelivered to the dead-letter topic ({@code dead_lettered_at} is null) until the broker
 * acknowledges; when it is back the dead letter is published and the row is marked. The outage is a
 * paused container: connections hang, as they do when a broker is gone from the network.
 */
@SpringBootTest
class KafkaOutageTest extends AbstractNotificationTest {

  private static KafkaFixture kafka;

  private static synchronized KafkaFixture kafka() {
    if (kafka == null) {
      kafka = KafkaFixture.dedicated();
    }
    return kafka;
  }

  @DynamicPropertySource
  static void kafkaProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.kafka.bootstrap-servers", () -> kafka().bootstrapServers());
  }

  @Autowired DeliveryDispatcher dispatcher;

  @AfterAll
  static void stopBroker() {
    kafka().resume();
    kafka().stop();
  }

  /** Never leave the broker paused for the tests that run after this one. */
  @AfterEach
  void brokerIsBack() {
    kafka().resume();
  }

  @Test
  @DisplayName(
      "BR-08 a dead letter that Kafka could not take is published after the outage, not lost")
  void deadLetterSurvivesAKafkaOutage() {
    String eventId = CaseEvents.eventId();
    UUID id = seed("handler-a", eventId, "CASE_OPENED");
    provider.failAll();

    kafka().pause();
    for (int attempt = 1; attempt <= 5; attempt++) {
      // The dispatcher must not throw because Kafka is down.
      dispatcher.dispatchDue();
      elapse(Duration.ofMinutes(10));
    }

    assertThat(row(id).getStatus()).isEqualTo(NotificationStatus.FAILED);
    assertThat(row(id).getDeadLetteredAt()).isNull();

    kafka().resume();
    await()
        .atMost(Duration.ofSeconds(90))
        .pollInterval(Duration.ofSeconds(1))
        .untilAsserted(
            () -> {
              elapse(Duration.ofMinutes(10));
              dispatcher.dispatchDue();
              assertThat(row(id).getDeadLetteredAt()).isNotNull();
            });
    // More runs publish nothing again.
    elapse(Duration.ofMinutes(10));
    dispatcher.dispatchDue();
    dispatcher.dispatchDue();

    // At least once: an attempt the outage cut off can still reach the broker when it resumes, so
    // the dead letter may be there twice with the same key (which is the event id, for consumers
    // to dedupe). It must be there, never more than twice, and the delivery is not attempted again.
    List<ConsumerRecord<String, String>> dead =
        kafka()
            .readByKey(
                Topics.DEAD_LETTER, eventId, 1, Duration.ofSeconds(4), Duration.ofSeconds(60));
    assertThat(dead).hasSizeBetween(1, 2);
    assertThat(provider.attemptedIds()).hasSize(5);
    assertThat(row(id).getStatus()).isEqualTo(NotificationStatus.FAILED);
  }
}
