package com.aerotrace.notification.base;

import com.aerotrace.notification.delivery.MockNotificationProvider;
import com.aerotrace.notification.model.entity.NotificationEntity;
import com.aerotrace.notification.repository.NotificationRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * A real PostgreSQL built by Liquibase (see the parent) and the mock delivery provider. Every test
 * starts with an empty {@code notification} table and a healthy provider. Subclasses are
 * {@code @SpringBootTest}s.
 *
 * <p>Delivery times are the database's {@code now()} (ADR 0005, decision 9), so tests do not move a
 * clock: they move the rows. {@link #elapse} shifts every stored time back, which is the same as
 * that much time passing; {@link #dbNow} is the database's time for bounds. Every helper raises
 * {@code version}, as the schema requires of every update.
 */
public abstract class AbstractNotificationTest extends AbstractTestContainerConfiguration {

  private static final String TABLE = "notification.notification";

  @Autowired protected MockNotificationProvider provider;
  @Autowired protected NotificationRepository notifications;
  @Autowired protected TestSql sql;

  @BeforeEach
  void cleanSlate() {
    notifications.deleteAllInBatch();
    provider.reset();
  }

  /** Writes a pending notification, created and due now; returns its id. */
  protected UUID seed(String recipient, String eventId, String template) {
    UUID id = UUID.randomUUID();
    int inserted =
        notifications.insertIfAbsent(
            id,
            recipient,
            eventId,
            template,
            UUID.randomUUID(),
            CaseEvents.bagTag(),
            "OPEN",
            1L,
            CaseEvents.OCCURRED_AT,
            CaseEvents.CORRELATION_ID);
    if (inserted != 1) {
      throw new IllegalStateException("the test row already existed");
    }
    return id;
  }

  /** The database's current time. */
  protected Instant dbNow() {
    return sql.now();
  }

  /** As if {@code by} passed: every stored time of every row moves back by {@code by}. */
  protected void elapse(Duration by) {
    String back = " - make_interval(secs => ?)";
    double s = seconds(by);
    sql.update(
        "update "
            + TABLE
            + " set next_attempt_at = next_attempt_at"
            + back
            + ", created_at = created_at"
            + back
            + ", sent_at = sent_at"
            + back
            + ", failed_at = failed_at"
            + back
            + ", dead_lettered_at = dead_lettered_at"
            + back
            + ", version = version + 1",
        s,
        s,
        s,
        s,
        s);
  }

  /** The row is next due {@code in} from now (negative: already overdue). */
  protected void dueIn(UUID id, Duration in) {
    sql.update(
        "update "
            + TABLE
            + " set next_attempt_at = now() + make_interval(secs => ?), version = version + 1"
            + " where id = ?",
        seconds(in),
        id);
  }

  /** The row was created {@code ago} before now. */
  protected void createdAgo(UUID id, Duration ago) {
    sql.update(
        "update "
            + TABLE
            + " set created_at = now() - make_interval(secs => ?), version = version + 1"
            + " where id = ?",
        seconds(ago),
        id);
  }

  protected List<NotificationEntity> rowsOf(String eventId) {
    return notifications.findAll().stream().filter(n -> n.getEventId().equals(eventId)).toList();
  }

  protected NotificationEntity row(UUID id) {
    return notifications.findById(id).orElseThrow();
  }

  private static double seconds(Duration duration) {
    return duration.getSeconds() + duration.getNano() / 1_000_000_000.0;
  }
}
