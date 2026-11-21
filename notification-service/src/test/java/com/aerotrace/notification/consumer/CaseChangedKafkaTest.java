package com.aerotrace.notification.consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.aerotrace.common.event.DeadLetterPayload;
import com.aerotrace.common.event.EventEnvelope;
import com.aerotrace.common.event.EventJson;
import com.aerotrace.common.event.Topics;
import com.aerotrace.common.model.RecoveryState;
import com.aerotrace.notification.base.AbstractNotificationTest;
import com.aerotrace.notification.base.CaseEvents;
import com.aerotrace.notification.base.KafkaFixture;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * The case-changed consumer against a real Kafka: a duplicate or redelivered event is one
 * notification, a poison event is dead-lettered without blocking the events behind it, and a
 * transient failure is retried before it is dead-lettered.
 */
@SpringBootTest
class CaseChangedKafkaTest extends AbstractNotificationTest {

  private static final String LISTENER_ID = "notification-case-changed";
  private static final String GROUP = "notification-test-" + UUID.randomUUID();

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
    registry.add("aerotrace.notification.consumers.enabled", () -> "true");
    registry.add("spring.kafka.consumer.group-id", () -> GROUP);
    registry.add("spring.kafka.consumer.auto-offset-reset", () -> "earliest");
    registry.add("spring.kafka.producer.properties.max.block.ms", () -> "10000");
    registry.add("spring.kafka.producer.properties.request.timeout.ms", () -> "10000");
    registry.add("spring.kafka.producer.properties.delivery.timeout.ms", () -> "20000");
  }

  @Autowired KafkaListenerEndpointRegistry registry;
  @MockitoSpyBean CaseChangedHandler handler;

  // ------------------------------------------------------------------ helpers

  private static String event(String eventId, String handlerId) {
    return CaseEvents.event(
        eventId,
        CaseEvents.caseId(),
        CaseEvents.bagTag(),
        RecoveryState.OPEN,
        1,
        "HIGH_CONNECTION_RISK",
        handlerId);
  }

  private static void publish(String eventId, String json) throws Exception {
    kafka().publish(Topics.CASE_CHANGED, eventId, json);
  }

  private void awaitRows(String eventId, int expected) {
    await()
        .atMost(Duration.ofSeconds(60))
        .untilAsserted(() -> assertThat(rowsOf(eventId)).hasSize(expected));
  }

  /**
   * A marker event on the same partition, published after the events under test: once its
   * notification exists, everything before it has been handled, so "nothing more happened" can be
   * asserted without a sleep.
   */
  private void awaitMarker() throws Exception {
    String markerId = CaseEvents.eventId();
    publish(markerId, event(markerId, CaseEvents.handler()));
    awaitRows(markerId, 1);
  }

  private static List<ConsumerRecord<String, String>> deadLetters(String key, int expected) {
    return kafka()
        .readByKey(
            Topics.DEAD_LETTER, key, expected, Duration.ofSeconds(3), Duration.ofSeconds(60));
  }

  // ------------------------------------------------------------------ AT-13

  @Test
  @DisplayName(
      "AT-13 the same recovery.case.changed.v1 event published twice makes one notification")
  void eventPublishedTwiceMakesOneNotification() throws Exception {
    String eventId = CaseEvents.eventId();
    String json = event(eventId, CaseEvents.handler());

    publish(eventId, json);
    publish(eventId, json);
    awaitMarker();

    assertThat(rowsOf(eventId)).hasSize(1);
    verify(handler, times(2)).handle(json);
  }

  @Test
  @DisplayName(
      "AT-13 an event redelivered after the consumer restarts makes no second notification")
  void redeliveryAfterRestartMakesNoSecondNotification() throws Exception {
    String eventId = CaseEvents.eventId();
    String json = event(eventId, CaseEvents.handler());
    publish(eventId, json);
    awaitRows(eventId, 1);

    // The consumer goes away and comes back having committed nothing past this event, as after a
    // crash between the database commit and the offset commit.
    var container = registry.getListenerContainer(LISTENER_ID);
    container.stop();
    kafka().rewind(GROUP, Topics.CASE_CHANGED);
    container.start();
    awaitMarker();

    verify(handler, atLeast(2)).handle(json);
    assertThat(rowsOf(eventId)).hasSize(1);
  }

  // ------------------------------------------------------------------ poison

  @Test
  @DisplayName("BR-08 an event in an unknown state is dead-lettered at once, without a retry")
  void unknownStateIsDeadLetteredAtOnce() throws Exception {
    String eventId = CaseEvents.eventId();
    String json =
        event(eventId, CaseEvents.handler())
            .replace("\"state\":\"OPEN\"", "\"state\":\"TELEPORTED\"");

    publish(eventId, json);

    List<ConsumerRecord<String, String>> dead = deadLetters(eventId, 1);
    assertThat(dead).hasSize(1);
    EventEnvelope<DeadLetterPayload> envelope =
        EventJson.read(dead.getFirst().value(), DeadLetterPayload.class);
    assertThat(envelope.payload().originalTopic()).isEqualTo(Topics.CASE_CHANGED);
    assertThat(envelope.payload().originalEventId()).isEqualTo(eventId);
    verify(handler, times(1)).handle(json);
    assertThat(rowsOf(eventId)).isEmpty();
  }

  @Test
  @DisplayName(
      "BR-08 text that is not an event is dead-lettered at once and does not block the next")
  void garbageIsDeadLetteredAtOnceAndDoesNotBlock() throws Exception {
    String garbage = "garbage { " + UUID.randomUUID();

    publish("garbage", garbage);
    awaitMarker();

    verify(handler, times(1)).handle(garbage);
  }

  @Test
  @DisplayName("BR-08 a transient database failure is retried, then the event is dead-lettered")
  void transientFailureIsRetriedThenDeadLettered() throws Exception {
    String eventId = CaseEvents.eventId();
    String json = event(eventId, CaseEvents.handler());
    doThrow(new TransientDataAccessResourceException("database is down"))
        .when(handler)
        .handle(argThat((String s) -> s != null && s.contains(eventId)));

    publish(eventId, json);

    List<ConsumerRecord<String, String>> dead = deadLetters(eventId, 1);
    assertThat(dead).hasSize(1);
    EventEnvelope<DeadLetterPayload> envelope =
        EventJson.read(dead.getFirst().value(), DeadLetterPayload.class);
    assertThat(envelope.payload().originalTopic()).isEqualTo(Topics.CASE_CHANGED);
    assertThat(envelope.payload().reason()).isNotBlank();
    // retry-attempts is 3 in the test profile: the first delivery and two retries.
    verify(handler, times(3)).handle(json);
    assertThat(rowsOf(eventId)).isEmpty();
    awaitMarker();
  }

  @Test
  @DisplayName("BR-08 a transient failure that passes on a retry is handled and not dead-lettered")
  void transientFailureThatPassesIsNotDeadLettered() throws Exception {
    String eventId = CaseEvents.eventId();
    String json = event(eventId, CaseEvents.handler());
    doThrow(new TransientDataAccessResourceException("database is down"))
        .doThrow(new TransientDataAccessResourceException("database is down"))
        .doCallRealMethod()
        .when(handler)
        .handle(argThat((String s) -> s != null && s.contains(eventId)));

    publish(eventId, json);

    awaitRows(eventId, 1);
    verify(handler, times(3)).handle(json);
    assertThat(deadLetters(eventId, 0)).isEmpty();
  }
}
