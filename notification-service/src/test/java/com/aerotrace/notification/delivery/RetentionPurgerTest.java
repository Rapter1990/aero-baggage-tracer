package com.aerotrace.notification.delivery;

import static org.assertj.core.api.Assertions.assertThat;

import com.aerotrace.notification.base.AbstractNotificationTest;
import com.aerotrace.notification.base.CaseEvents;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** ADR 0005, decision 14: only finished rows older than the retention (90 days) are purged. */
@SpringBootTest(properties = "aerotrace.notification.purge.batch-size=2")
class RetentionPurgerTest extends AbstractNotificationTest {

  private static final Duration RETENTION = Duration.ofDays(90);

  @Autowired RetentionPurger purger;
  @MockitoBean KafkaTemplate<String, String> kafka;

  /** A row of {@code status} created {@code age} ago (by the database's time). */
  private UUID seedWith(Duration age, String status, boolean deadLettered) {
    UUID id = seed(CaseEvents.handler(), CaseEvents.eventId(), "CASE_OPENED");
    createdAgo(id, age);
    jdbc.update(
        "update notification.notification set status = ?, version = version + 1,"
            + " sent_at = case when ? = 'SENT' then created_at end,"
            + " failed_at = case when ? = 'FAILED' then created_at end,"
            + " dead_lettered_at = case when ? then created_at end where id = ?",
        status,
        status,
        status,
        deadLettered,
        id);
    return id;
  }

  @Test
  @DisplayName("BR-08 the purge deletes old SENT rows and old dead-lettered FAILED rows only")
  void purgeDeletesOnlyOldFinishedRows() {
    Duration old = RETENTION.plusSeconds(1);
    Duration young = RETENTION.minusSeconds(60);
    UUID oldSent1 = seedWith(old, "SENT", false);
    UUID oldSent2 = seedWith(old, "SENT", false);
    UUID oldDeadLettered = seedWith(old, "FAILED", true);
    UUID oldPending = seedWith(old, "PENDING", false);
    UUID oldUndelivered = seedWith(old, "FAILED", false);
    UUID youngSent = seedWith(young, "SENT", false);

    // Three rows in batches of two: the purge keeps going until a batch is not full.
    assertThat(purger.purge()).isEqualTo(3);

    assertThat(notifications.findAllById(List.of(oldSent1, oldSent2, oldDeadLettered))).isEmpty();
    assertThat(notifications.findAllById(List.of(oldPending, oldUndelivered, youngSent)))
        .hasSize(3);
    assertThat(purger.purge()).isZero();
  }
}
