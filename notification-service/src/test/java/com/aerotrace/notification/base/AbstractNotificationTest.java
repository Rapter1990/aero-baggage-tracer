package com.aerotrace.notification.base;

import com.aerotrace.notification.delivery.MockNotificationProvider;
import com.aerotrace.notification.model.entity.NotificationEntity;
import com.aerotrace.notification.repository.NotificationRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

/**
 * A real PostgreSQL built by Liquibase (see the parent), a clock the test moves by hand and the
 * mock delivery provider. Every test starts with an empty {@code notification} table, a healthy
 * provider and the clock at {@link MutableClock#T0}. Subclasses are {@code @SpringBootTest}s.
 */
@Import(TestClockConfiguration.class)
public abstract class AbstractNotificationTest extends AbstractTestContainerConfiguration {

  @Autowired protected MutableClock clock;
  @Autowired protected MockNotificationProvider provider;
  @Autowired protected NotificationRepository notifications;

  @BeforeEach
  void cleanSlate() {
    notifications.deleteAll();
    provider.reset();
    clock.set(MutableClock.T0);
  }

  /** Writes a pending notification that is due at {@code createdAt}; returns its id. */
  protected UUID seed(String recipient, String eventId, String template, Instant createdAt) {
    UUID id = UUID.randomUUID();
    int inserted =
        notifications.insertIfAbsent(
            id,
            recipient,
            eventId,
            template,
            "case-" + id,
            CaseEvents.bagTag(),
            "OPEN",
            1L,
            createdAt);
    if (inserted != 1) {
      throw new IllegalStateException("the test row already existed");
    }
    return id;
  }

  protected List<NotificationEntity> rowsOf(String eventId) {
    return notifications.findAll().stream().filter(n -> n.getEventId().equals(eventId)).toList();
  }

  protected NotificationEntity row(UUID id) {
    return notifications.findById(id).orElseThrow();
  }
}
