package com.aerotrace.notification.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.aerotrace.notification.base.AbstractNotificationTest;
import com.aerotrace.notification.model.entity.NotificationEntity;
import com.aerotrace.notification.model.entity.NotificationStatus;
import java.time.Instant;
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
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** BR-08 against the real schema: the unique key is the guard, whatever the writers do. */
@SpringBootTest
class NotificationRepositoryTest extends AbstractNotificationTest {

  private static final Instant AT = Instant.parse("2026-03-01T09:00:00Z");

  @MockitoBean KafkaTemplate<String, String> kafka;

  private int insert(String recipient, String eventId, String template) {
    return notifications.insertIfAbsent(
        UUID.randomUUID(),
        recipient,
        eventId,
        template,
        UUID.randomUUID(),
        "0123456789",
        "OPEN",
        1L,
        AT,
        "corr-test");
  }

  @Test
  @DisplayName("BR-08 the table has a unique constraint on (recipient, event_id, template)")
  void uniqueConstraintExists() {
    List<String> constraints =
        jdbc.queryForList(
            "select pg_get_constraintdef(oid) from pg_constraint"
                + " where conrelid = 'notification.notification'::regclass and contype = 'u'",
            String.class);

    assertThat(constraints)
        .anySatisfy(
            definition ->
                assertThat(definition.replace("\"", ""))
                    .isEqualTo("UNIQUE (recipient, event_id, template)"));
  }

  @Test
  @DisplayName("BR-08 the same (recipient, eventId, template) is stored once")
  void sameKeyIsStoredOnce() {
    assertThat(insert("handler-a", "EV-1", "CASE_OPENED")).isEqualTo(1);
    assertThat(insert("handler-a", "EV-1", "CASE_OPENED")).isZero();

    assertThat(rowsOf("EV-1")).hasSize(1);
  }

  @Test
  @DisplayName("BR-08 another recipient, event or template is another notification")
  void anotherKeyPartIsAnotherNotification() {
    assertThat(insert("handler-a", "EV-1", "CASE_OPENED")).isEqualTo(1);
    assertThat(insert("handler-b", "EV-1", "CASE_OPENED")).isEqualTo(1);
    assertThat(insert("handler-a", "EV-2", "CASE_OPENED")).isEqualTo(1);
    assertThat(insert("handler-a", "EV-1", "CASE_RESOLVED")).isEqualTo(1);

    assertThat(notifications.count()).isEqualTo(4);
  }

  @Test
  @DisplayName("AT-13 sixteen writers racing on one key leave one row and exactly one winner")
  void racingWritersLeaveOneRow() throws Exception {
    int writers = 16;
    CountDownLatch ready = new CountDownLatch(writers);
    CountDownLatch go = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(writers);
    try {
      List<Future<Integer>> results = new ArrayList<>();
      for (int i = 0; i < writers; i++) {
        results.add(
            pool.submit(
                () -> {
                  ready.countDown();
                  go.await();
                  return insert("handler-race", "EV-RACE", "CASE_OPENED");
                }));
      }
      assertThat(ready.await(30, TimeUnit.SECONDS)).isTrue();
      go.countDown();
      int winners = 0;
      for (Future<Integer> result : results) {
        winners += result.get(30, TimeUnit.SECONDS);
      }

      assertThat(winners).isEqualTo(1);
      assertThat(rowsOf("EV-RACE")).hasSize(1);
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  @DisplayName("BR-08 a new notification is pending, not attempted, not dead-lettered")
  void newNotificationStartsPending() {
    Instant before = dbNow();
    insert("handler-a", "EV-NEW", "CASE_OPENED");
    Instant after = dbNow();

    NotificationEntity stored = rowsOf("EV-NEW").getFirst();

    assertThat(stored.getStatus()).isEqualTo(NotificationStatus.PENDING);
    assertThat(stored.getAttempts()).isZero();
    // Due at once, by the database's time.
    assertThat(stored.getNextAttemptAt()).isBetween(before, after);
    assertThat(stored.getCreatedAt()).isEqualTo(stored.getNextAttemptAt());
    assertThat(stored.getDeadLetteredAt()).isNull();
    assertThat(stored.getSentAt()).isNull();
  }
}
