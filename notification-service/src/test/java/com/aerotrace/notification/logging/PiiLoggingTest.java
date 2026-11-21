package com.aerotrace.notification.logging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.aerotrace.common.event.Topics;
import com.aerotrace.common.model.RecoveryState;
import com.aerotrace.notification.base.AbstractNotificationTest;
import com.aerotrace.notification.base.CaseEvents;
import com.aerotrace.notification.consumer.CaseChangedHandler;
import com.aerotrace.notification.consumer.PoisonEventException;
import com.aerotrace.notification.delivery.DeliveryDispatcher;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Personal data stays out of logs and out of the database (ADR 0005, decision 4): the recipient, a
 * passenger reference and the case's free-text reason are never logged or stored. Debug logging is
 * switched on so that nothing hides behind a level; the log must still show the case id, the event
 * id and the template, so the test cannot pass by the service logging nothing at all.
 */
@SpringBootTest(properties = "logging.level.com.aerotrace.notification=DEBUG")
@ExtendWith(OutputCaptureExtension.class)
class PiiLoggingTest extends AbstractNotificationTest {

  private static final String RECIPIENT = "pii-recipient-7f3a91c2";
  private static final String PASSENGER_REF = "PAX-4471-ZX";
  private static final String REASON =
      "Maria Gonzalez (passport P1234567, " + PASSENGER_REF + ") is waiting at gate B12";
  private static final List<String> SECRETS =
      List.of(RECIPIENT, PASSENGER_REF, "Maria", "Gonzalez", "P1234567", "gate B12", REASON);

  @Autowired CaseChangedHandler handler;
  @Autowired DeliveryDispatcher dispatcher;
  @Autowired JdbcTemplate jdbc;
  @MockitoBean KafkaTemplate<String, String> kafka;

  private static void assertNoSecrets(String text) {
    for (String secret : SECRETS) {
      assertThat(text).as("must not contain '%s'", secret).doesNotContain(secret);
    }
  }

  @Test
  @DisplayName(
      "BR-08 handling, duplicates, failed deliveries and dead letters never log personal data")
  void logsCarryNoPersonalData(CapturedOutput output) {
    when(kafka.send(eq(Topics.DEAD_LETTER), anyString(), anyString()))
        .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));
    String eventId = CaseEvents.eventId();
    String caseId = CaseEvents.caseId();
    String json =
        CaseEvents.event(
            eventId, caseId, CaseEvents.bagTag(), RecoveryState.RESERVED, 2, REASON, RECIPIENT);

    handler.handle(json);
    handler.handle(json);
    provider.failAll();
    for (int attempt = 1; attempt <= 5; attempt++) {
      dispatcher.dispatchDue();
      clock.advance(Duration.ofMinutes(10));
    }
    String poison =
        CaseEvents.event(
                CaseEvents.eventId(),
                caseId,
                CaseEvents.bagTag(),
                RecoveryState.OPEN,
                1,
                REASON,
                RECIPIENT)
            .replace("\"state\":\"OPEN\"", "\"state\":\"TELEPORTED\"");
    assertThatThrownBy(() -> handler.handle(poison))
        .isInstanceOf(PoisonEventException.class)
        .satisfies(e -> assertNoSecrets(String.valueOf(e.getMessage())));
    handler.handle(
        CaseEvents.event(
            CaseEvents.eventId(),
            caseId,
            CaseEvents.bagTag(),
            RecoveryState.OPEN,
            1,
            REASON,
            null));

    String log = output.getAll();
    assertNoSecrets(log);
    // Positive control: what the log may carry is there.
    assertThat(log).contains(eventId).contains(caseId).contains("CASE_RESERVED");
  }

  @Test
  @DisplayName("BR-08 the notification table has no column for the reason or a passenger reference")
  void tableHasNoColumnForPersonalData() {
    List<String> columns =
        jdbc.queryForList(
            "select column_name from information_schema.columns"
                + " where table_schema = 'notification' and table_name = 'notification'",
            String.class);

    assertThat(columns).isNotEmpty();
    assertThat(columns)
        .noneMatch(c -> c.contains("reason") || c.contains("passenger") || c.contains("message"));
  }

  @Test
  @DisplayName("BR-08 a stored notification holds no reason and no passenger reference")
  void storedRowHoldsNoPersonalData() {
    String eventId = CaseEvents.eventId();
    handler.handle(
        CaseEvents.event(
            eventId,
            CaseEvents.caseId(),
            CaseEvents.bagTag(),
            RecoveryState.OPEN,
            1,
            REASON,
            RECIPIENT));

    List<String> rows =
        jdbc.queryForList(
            "select n::text from notification.notification n where n.event_id = ?",
            String.class,
            eventId);

    assertThat(rows).hasSize(1);
    // The recipient is the one personal value a row must keep: it says whose notification it is.
    assertThat(rows.getFirst()).contains(RECIPIENT);
    for (String secret : SECRETS) {
      if (!secret.equals(RECIPIENT)) {
        assertThat(rows.getFirst()).as("row must not contain '%s'", secret).doesNotContain(secret);
      }
    }
  }
}
