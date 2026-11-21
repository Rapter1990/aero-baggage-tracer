package com.aerotrace.notification.consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aerotrace.common.model.RecoveryState;
import com.aerotrace.notification.base.AbstractNotificationTest;
import com.aerotrace.notification.base.CaseEvents;
import com.aerotrace.notification.domain.TemplateSelector;
import com.aerotrace.notification.model.entity.NotificationEntity;
import com.aerotrace.notification.model.entity.NotificationStatus;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * The handler against a real PostgreSQL, called directly (the Kafka listeners are off). AT-13 and
 * BR-08: one event, one notification, however often and however concurrently it arrives.
 */
@SpringBootTest
class CaseChangedHandlerTest extends AbstractNotificationTest {

  @Autowired CaseChangedHandler handler;
  @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
  @MockitoBean KafkaTemplate<String, String> kafka;

  private final TemplateSelector templates = new TemplateSelector();

  // ------------------------------------------------------------------ AT-13

  @Test
  @DisplayName("AT-13 an assigned case event creates one notification for its handler")
  void eventCreatesOneNotification() {
    String eventId = CaseEvents.eventId();
    String handlerId = CaseEvents.handler();
    String caseId = "case-" + UUID.randomUUID();
    String bag = CaseEvents.bagTag();

    CaseChangedHandler.Outcome outcome =
        handler.handle(
            CaseEvents.event(eventId, caseId, bag, RecoveryState.OPEN, 1, "reason", handlerId));

    assertThat(outcome).isEqualTo(CaseChangedHandler.Outcome.NOTIFICATION_CREATED);
    assertThat(rowsOf(eventId))
        .singleElement()
        .satisfies(
            n -> {
              assertThat(n.getRecipient()).isEqualTo(handlerId);
              assertThat(n.getTemplate()).isEqualTo("CASE_OPENED");
              assertThat(n.getCaseId()).isEqualTo(caseId);
              assertThat(n.getBagTag()).isEqualTo(bag);
              assertThat(n.getCaseState()).isEqualTo(RecoveryState.OPEN);
              assertThat(n.getCaseVersion()).isEqualTo(1);
              assertThat(n.getStatus()).isEqualTo(NotificationStatus.PENDING);
              assertThat(n.getAttempts()).isZero();
              assertThat(n.getCreatedAt()).isEqualTo(clock.instant());
            });
  }

  @Test
  @DisplayName(
      "AT-13 the same recovery.case.changed.v1 event handled twice leaves one notification")
  void sameEventTwiceLeavesOneNotification() {
    String eventId = CaseEvents.eventId();
    String json =
        CaseEvents.event(
            eventId,
            "case-1",
            CaseEvents.bagTag(),
            RecoveryState.RESERVED,
            3,
            "reason",
            CaseEvents.handler());

    CaseChangedHandler.Outcome first = handler.handle(json);
    CaseChangedHandler.Outcome second = handler.handle(json);

    assertThat(first).isEqualTo(CaseChangedHandler.Outcome.NOTIFICATION_CREATED);
    assertThat(second).isEqualTo(CaseChangedHandler.Outcome.DUPLICATE);
    assertThat(rowsOf(eventId)).hasSize(1);
  }

  @Test
  @DisplayName("AT-13 a duplicate does not reset a notification that was already delivered")
  void duplicateLeavesTheDeliveredRowAlone() {
    String eventId = CaseEvents.eventId();
    String json =
        CaseEvents.event(
            eventId,
            "case-1",
            CaseEvents.bagTag(),
            RecoveryState.OPEN,
            1,
            "reason",
            CaseEvents.handler());
    handler.handle(json);
    jdbc.update(
        "update notification.notification set status = 'SENT', attempts = 1 where event_id = ?",
        eventId);

    handler.handle(json);

    assertThat(rowsOf(eventId))
        .singleElement()
        .satisfies(n -> assertThat(n.getStatus()).isEqualTo(NotificationStatus.SENT));
  }

  @Test
  @DisplayName("AT-13 sixteen threads handling the same event at once create one notification")
  void parallelHandlingOfTheSameEventCreatesOne() throws Exception {
    int threads = 16;
    String eventId = CaseEvents.eventId();
    String json =
        CaseEvents.event(
            eventId,
            "case-1",
            CaseEvents.bagTag(),
            RecoveryState.ASSESSING,
            2,
            "reason",
            CaseEvents.handler());
    CountDownLatch ready = new CountDownLatch(threads);
    CountDownLatch go = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    try {
      List<Future<CaseChangedHandler.Outcome>> outcomes = new ArrayList<>();
      for (int i = 0; i < threads; i++) {
        outcomes.add(
            pool.submit(
                () -> {
                  ready.countDown();
                  go.await();
                  return handler.handle(json);
                }));
      }
      assertThat(ready.await(30, TimeUnit.SECONDS)).isTrue();
      go.countDown();
      int created = 0;
      int duplicates = 0;
      for (Future<CaseChangedHandler.Outcome> outcome : outcomes) {
        // No thread may fail: losing the race is a duplicate, not an error to retry.
        switch (outcome.get(30, TimeUnit.SECONDS)) {
          case NOTIFICATION_CREATED -> created++;
          case DUPLICATE -> duplicates++;
          default -> throw new AssertionError("unexpected outcome");
        }
      }

      assertThat(created).isEqualTo(1);
      assertThat(duplicates).isEqualTo(threads - 1);
      assertThat(rowsOf(eventId)).hasSize(1);
    } finally {
      pool.shutdownNow();
    }
  }

  // ------------------------------------------------------------------ BR-08 templates and replays

  @Test
  @DisplayName("BR-08 every state notifies, with the template of that state")
  void everyStateNotifiesWithItsTemplate() {
    for (RecoveryState state : RecoveryState.values()) {
      String eventId = CaseEvents.eventId();

      CaseChangedHandler.Outcome outcome =
          handler.handle(
              CaseEvents.event(
                  eventId,
                  "case-1",
                  CaseEvents.bagTag(),
                  state,
                  1,
                  "reason",
                  CaseEvents.handler()));

      assertThat(outcome)
          .as("outcome for %s", state)
          .isEqualTo(CaseChangedHandler.Outcome.NOTIFICATION_CREATED);
      assertThat(rowsOf(eventId))
          .as("row for %s", state)
          .singleElement()
          .satisfies(n -> assertThat(n.getTemplate()).isEqualTo(templates.select(state)));
    }
  }

  @Test
  @DisplayName("BR-08 a replayed older version has its own event id and so its own notification")
  void replayedOlderVersionNotifiesAgain() {
    String handlerId = CaseEvents.handler();
    String bag = CaseEvents.bagTag();
    String v1 = CaseEvents.eventId();
    String v2 = CaseEvents.eventId();
    String v1Replayed = CaseEvents.eventId();
    handler.handle(CaseEvents.event(v1, "case-9", bag, RecoveryState.OPEN, 1, "r", handlerId));
    handler.handle(CaseEvents.event(v2, "case-9", bag, RecoveryState.ASSESSING, 2, "r", handlerId));

    CaseChangedHandler.Outcome outcome =
        handler.handle(
            CaseEvents.event(v1Replayed, "case-9", bag, RecoveryState.OPEN, 1, "r", handlerId));

    // There is no ordering filter: the unique key is the only deduplication (ADR 0005, decision 2).
    assertThat(outcome).isEqualTo(CaseChangedHandler.Outcome.NOTIFICATION_CREATED);
    assertThat(notifications.findAll().stream().filter(n -> n.getRecipient().equals(handlerId)))
        .hasSize(3);
  }

  @Test
  @DisplayName("BR-08 two handlers of one event are two notifications, one each")
  void anotherRecipientIsAnotherNotification() {
    String eventId = CaseEvents.eventId();
    String bag = CaseEvents.bagTag();

    handler.handle(
        CaseEvents.event(eventId, "case-1", bag, RecoveryState.OPEN, 1, "r", "handler-one"));
    handler.handle(
        CaseEvents.event(eventId, "case-1", bag, RecoveryState.OPEN, 1, "r", "handler-two"));

    assertThat(rowsOf(eventId))
        .extracting(NotificationEntity::getRecipient)
        .containsExactlyInAnyOrder("handler-one", "handler-two");
  }

  // ------------------------------------------------------------------ no recipient

  @Test
  @DisplayName("BR-08 a revision 1 event (no assignedTo) is accepted and creates no notification")
  void revisionOneEventIsAcceptedWithoutNotification() {
    String eventId = CaseEvents.eventId();

    CaseChangedHandler.Outcome outcome =
        handler.handle(
            CaseEvents.revision1(eventId, "case-1", CaseEvents.bagTag(), RecoveryState.OPEN, 1));

    assertThat(outcome).isEqualTo(CaseChangedHandler.Outcome.NO_RECIPIENT);
    assertThat(rowsOf(eventId)).isEmpty();
  }

  @Test
  @DisplayName("BR-08 an unassigned case (revision 2, assignedTo null) creates no notification")
  void unassignedCaseCreatesNoNotification() {
    String eventId = CaseEvents.eventId();

    CaseChangedHandler.Outcome outcome =
        handler.handle(
            CaseEvents.event(
                eventId, "case-1", CaseEvents.bagTag(), RecoveryState.OPEN, 1, "reason", null));

    assertThat(outcome).isEqualTo(CaseChangedHandler.Outcome.NO_RECIPIENT);
    assertThat(rowsOf(eventId)).isEmpty();
  }

  // ------------------------------------------------------------------ poison

  @Test
  @DisplayName("BR-08 text that is not an event is poison, refused for good")
  void garbageIsPoison() {
    assertThatThrownBy(() -> handler.handle("garbage {")).isInstanceOf(PoisonEventException.class);
  }

  @Test
  @DisplayName("BR-08 an event without an eventId is poison, so it can never be deduplicated")
  void missingEventIdIsPoison() {
    String eventId = CaseEvents.eventId();
    String json =
        CaseEvents.event(
                eventId, "case-1", CaseEvents.bagTag(), RecoveryState.OPEN, 1, "r", "handler-x")
            .replace("\"eventId\":\"" + eventId + "\"", "\"eventId\":null");

    assertThatThrownBy(() -> handler.handle(json)).isInstanceOf(PoisonEventException.class);
    assertThat(notifications.count()).isZero();
  }

  @Test
  @DisplayName("BR-08 an event in a state this service does not know is poison")
  void unknownStateIsPoison() {
    String eventId = CaseEvents.eventId();
    String json =
        CaseEvents.event(
                eventId, "case-1", CaseEvents.bagTag(), RecoveryState.OPEN, 1, "r", "handler-x")
            .replace("\"state\":\"OPEN\"", "\"state\":\"TELEPORTED\"");

    assertThatThrownBy(() -> handler.handle(json))
        .isInstanceOf(PoisonEventException.class)
        .satisfies(e -> assertThat(e.getMessage()).doesNotContain("handler-x", "TELEPORTED"));
    assertThat(notifications.count()).isZero();
  }

  @Test
  @DisplayName("BR-08 an event of another type is poison")
  void wrongEventTypeIsPoison() {
    String eventId = CaseEvents.eventId();
    String json =
        CaseEvents.event(
                eventId, "case-1", CaseEvents.bagTag(), RecoveryState.OPEN, 1, "r", "handler-x")
            .replace("recovery.case.changed", "baggage.risk.changed");

    assertThatThrownBy(() -> handler.handle(json)).isInstanceOf(PoisonEventException.class);
    assertThat(notifications.count()).isZero();
  }

  @Test
  @DisplayName("BR-08 an event without a payload is poison")
  void missingPayloadIsPoison() {
    String json =
        CaseEvents.event(
                CaseEvents.eventId(),
                "case-1",
                CaseEvents.bagTag(),
                RecoveryState.OPEN,
                1,
                "r",
                "handler-x")
            .replaceAll("\"payload\":\\{.*}", "\"payload\":null}");

    assertThatThrownBy(() -> handler.handle(json)).isInstanceOf(PoisonEventException.class);
  }
}
