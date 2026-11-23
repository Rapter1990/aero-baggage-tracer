package com.aerotrace.notification.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.aerotrace.common.event.DeadLetterPayload;
import com.aerotrace.common.event.EventEnvelope;
import com.aerotrace.common.event.EventJson;
import com.aerotrace.common.event.Topics;
import com.aerotrace.notification.base.AbstractNotificationTest;
import com.aerotrace.notification.base.CaseEvents;
import com.aerotrace.notification.config.NotificationProperties;
import com.aerotrace.notification.constant.ProviderConstants;
import com.aerotrace.notification.model.entity.NotificationEntity;
import com.aerotrace.notification.model.entity.NotificationStatus;
import com.aerotrace.notification.repository.DeliveryRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * Delivery against a real PostgreSQL and a mock Kafka producer. Times are the database's {@code
 * now()}: a test lets time pass by moving the stored times back ({@code elapse}) and checks times
 * against {@code dbNow()} read before and after a run. The dispatcher is driven by hand; the
 * scheduler only fires once an hour. Defaults of the test profile: 5 attempts, 2 s base, 5 min
 * maximum, no jitter.
 */
@SpringBootTest
class DeliveryDispatcherTest extends AbstractNotificationTest {

  private static final int MAX_ATTEMPTS = 5;
  private static final Duration LEASE = Duration.ofSeconds(60);

  @Autowired DeliveryDispatcher dispatcher;
  @Autowired NotificationProperties properties;
  @Autowired BackoffPolicy backoff;
  @Autowired DeadLetterPublisher deadLetterPublisher;
  @Autowired MeterRegistry meters;
  @MockitoBean KafkaTemplate<String, String> kafka;
  @MockitoSpyBean DeliveryRepository deliveries;

  @BeforeEach
  void deadLetterTopicAcknowledges() {
    when(kafka.send(eq(Topics.DEAD_LETTER), anyString(), anyString()))
        .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));
  }

  /**
   * Every attempt is due, whatever the backoff was: ten minutes pass before each one, more than the
   * longest wait. Nothing passes after the last one.
   */
  private void runUntilExhausted() {
    for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
      if (attempt > 1) {
        elapse(Duration.ofMinutes(10));
      }
      dispatcher.dispatchDue();
    }
  }

  // ------------------------------------------------------------------ sending

  @Test
  @DisplayName("BR-08 a due notification is sent once and marked SENT")
  void dueNotificationIsSent() {
    UUID id = seed("handler-a", CaseEvents.eventId(), "CASE_OPENED");
    Instant before = dbNow();

    int processed = dispatcher.dispatchDue();

    assertThat(processed).isEqualTo(1);
    assertThat(provider.sentIds()).containsExactly(id);
    NotificationEntity sent = row(id);
    assertThat(sent.getStatus()).isEqualTo(NotificationStatus.SENT);
    assertThat(sent.getAttempts()).isEqualTo(1);
    // The database's time of the finish, not the application's.
    assertThat(sent.getSentAt()).isBetween(before, dbNow());
    verifyNoInteractions(kafka);
  }

  @Test
  @DisplayName("BR-08 a sent notification is never sent again")
  void sentNotificationIsNotSentAgain() {
    UUID id = seed("handler-a", CaseEvents.eventId(), "CASE_OPENED");
    dispatcher.dispatchDue();

    elapse(Duration.ofHours(1));
    int processed = dispatcher.dispatchDue();

    assertThat(processed).isZero();
    assertThat(provider.attemptedIds()).containsExactly(id);
  }

  @Test
  @DisplayName("BR-08 a notification that is not due yet is left alone")
  void notDueNotificationIsLeftAlone() {
    UUID id = seed("handler-a", CaseEvents.eventId(), "CASE_OPENED");
    dueIn(id, Duration.ofHours(1));

    int processed = dispatcher.dispatchDue();

    assertThat(processed).isZero();
    assertThat(provider.attemptedIds()).isEmpty();
    assertThat(row(id).getAttempts()).isZero();
  }

  @Test
  @DisplayName("BR-08 a finish that fails for one notification does not stop the others' finishes")
  @ExtendWith(OutputCaptureExtension.class)
  void failingFinishDoesNotAbortTheOthers(CapturedOutput output) {
    // Claimed oldest first: the broken row is the first of the batch.
    UUID broken = seed("handler-a", CaseEvents.eventId(), "CASE_OPENED");
    UUID second = seed("handler-b", CaseEvents.eventId(), "CASE_OPENED");
    UUID third = seed("handler-c", CaseEvents.eventId(), "CASE_OPENED");
    doThrow(new DataAccessResourceFailureException("connection to db-secret-host reset"))
        .when(deliveries)
        .markSent(eq(broken), anyLong());

    int processed = dispatcher.dispatchDue();

    assertThat(processed).isEqualTo(3);
    assertThat(provider.sentIds()).containsExactlyInAnyOrder(broken, second, third);
    assertThat(row(second).getStatus()).isEqualTo(NotificationStatus.SENT);
    assertThat(row(third).getStatus()).isEqualTo(NotificationStatus.SENT);
    // Not recorded: the row stays claimed and is due again when its lease ends.
    assertThat(row(broken).getStatus()).isEqualTo(NotificationStatus.PENDING);
    // Only the exception class is logged, never its message.
    assertThat(output.getAll())
        .contains("DataAccessResourceFailureException")
        .doesNotContain("db-secret-host");
  }

  // ------------------------------------------------------------------ BR-08 retries

  @Test
  @DisplayName("BR-08 delivery retries update the same row and never create a second notification")
  void retriesNeverCreateASecondRow() {
    String eventId = CaseEvents.eventId();
    UUID id = seed("handler-a", eventId, "CASE_OPENED");
    provider.failAll();

    for (int attempt = 1; attempt < MAX_ATTEMPTS; attempt++) {
      dispatcher.dispatchDue();
      elapse(Duration.ofMinutes(10));

      assertThat(rowsOf(eventId))
          .singleElement()
          .satisfies(n -> assertThat(n.getId()).isEqualTo(id));
      assertThat(notifications.count()).isEqualTo(1);
    }
    assertThat(row(id).getAttempts()).isEqualTo(MAX_ATTEMPTS - 1);
  }

  @Test
  @DisplayName("BR-08 after a failure the next attempt is scheduled with a doubling wait")
  void failureSchedulesTheNextAttempt() {
    UUID id = seed("handler-a", CaseEvents.eventId(), "CASE_OPENED");
    provider.failAll();

    // The wait after each failure is added to the database's now at the finish: 2, 4, 8, 16 s.
    int[] waits = {2, 4, 8, 16};
    for (int attempt = 1; attempt <= waits.length; attempt++) {
      if (attempt > 1) {
        elapse(Duration.ofSeconds(waits[attempt - 2]));
      }
      Instant before = dbNow();
      dispatcher.dispatchDue();
      Instant after = dbNow();

      NotificationEntity stored = row(id);
      assertThat(stored.getStatus()).isEqualTo(NotificationStatus.PENDING);
      assertThat(stored.getAttempts()).isEqualTo(attempt);
      Duration wait = Duration.ofSeconds(waits[attempt - 1]);
      assertThat(stored.getNextAttemptAt()).isBetween(before.plus(wait), after.plus(wait));
    }
  }

  @Test
  @DisplayName("BR-08 a notification is not retried before its next attempt is due")
  void notRetriedBeforeItIsDue() {
    UUID id = seed("handler-a", CaseEvents.eventId(), "CASE_OPENED");
    provider.failAll();
    dispatcher.dispatchDue();

    // The retry is due 2 s after the failure; only one of them has passed.
    elapse(Duration.ofSeconds(1));
    int early = dispatcher.dispatchDue();

    assertThat(early).isZero();
    assertThat(provider.attemptedIds()).containsExactly(id);
    elapse(Duration.ofSeconds(1));
    assertThat(dispatcher.dispatchDue()).isEqualTo(1);
    assertThat(provider.attemptedIds()).containsExactly(id, id);
  }

  @Test
  @DisplayName("BR-08 a delivery that fails twice and then works ends SENT, on the same row")
  void failsTwiceThenSucceeds() {
    String eventId = CaseEvents.eventId();
    UUID id = seed("handler-a", eventId, "CASE_OPENED");
    provider.failNext(2);

    for (int i = 0; i < 3; i++) {
      dispatcher.dispatchDue();
      elapse(Duration.ofMinutes(10));
    }

    NotificationEntity stored = row(id);
    assertThat(stored.getStatus()).isEqualTo(NotificationStatus.SENT);
    assertThat(stored.getAttempts()).isEqualTo(3);
    assertThat(provider.sentIds()).containsExactly(id);
    assertThat(rowsOf(eventId)).hasSize(1);
    verifyNoInteractions(kafka);
  }

  // ------------------------------------------------------------------ exhaustion

  @Test
  @DisplayName("BR-08 after the last attempt the notification is FAILED and dead-lettered once")
  void exhaustedNotificationIsFailedAndDeadLetteredOnce() {
    String eventId = CaseEvents.eventId();
    UUID id = seed("handler-a", eventId, "CASE_OPENED");
    provider.failAll();

    runUntilExhausted();

    NotificationEntity failed = row(id);
    assertThat(failed.getStatus()).isEqualTo(NotificationStatus.FAILED);
    assertThat(failed.getAttempts()).isEqualTo(MAX_ATTEMPTS);
    assertThat(failed.getDeadLetteredAt()).isNotNull();
    assertThat(provider.attemptedIds()).hasSize(MAX_ATTEMPTS);

    ArgumentCaptor<String> value = ArgumentCaptor.forClass(String.class);
    verify(kafka, times(1)).send(eq(Topics.DEAD_LETTER), eq(eventId), value.capture());
    EventEnvelope<DeadLetterPayload> envelope =
        EventJson.read(value.getValue(), DeadLetterPayload.class);
    assertThat(envelope.eventType()).isEqualTo("baggage.dead-letter");
    assertThat(envelope.payload().originalTopic()).isEqualTo(Topics.CASE_CHANGED);
    assertThat(envelope.payload().originalEventId()).isEqualTo(eventId);
    assertThat(envelope.payload().reason()).isEqualTo("DELIVERY_EXHAUSTED");
    assertThat(envelope.payload().attempts()).isEqualTo(MAX_ATTEMPTS);
    // The dead letter says what failed, never what the notification said or who it was for.
    assertThat(envelope.payload().rawPayload()).isNull();
    assertThat(value.getValue()).doesNotContain("handler-a");
  }

  @Test
  @DisplayName("BR-08 an exhausted notification is neither retried nor dead-lettered again")
  void exhaustedNotificationIsLeftAlone() {
    UUID id = seed("handler-a", CaseEvents.eventId(), "CASE_OPENED");
    provider.failAll();
    runUntilExhausted();

    for (int i = 0; i < 3; i++) {
      elapse(Duration.ofHours(1));
      dispatcher.dispatchDue();
    }

    assertThat(provider.attemptedIds()).hasSize(MAX_ATTEMPTS);
    verify(kafka, times(1)).send(eq(Topics.DEAD_LETTER), anyString(), anyString());
    assertThat(row(id).getStatus()).isEqualTo(NotificationStatus.FAILED);
  }

  @Test
  @DisplayName("BR-08 a failed dead-letter publish is retried, and not published twice")
  void failedDeadLetterPublishIsRetriedNotDuplicated() {
    String eventId = CaseEvents.eventId();
    UUID id = seed("handler-a", eventId, "CASE_OPENED");
    provider.failAll();
    when(kafka.send(eq(Topics.DEAD_LETTER), anyString(), anyString()))
        .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker is down")))
        .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));

    runUntilExhausted();

    // Kafka did not acknowledge: the notification is FAILED but not yet dead-lettered.
    assertThat(row(id).getStatus()).isEqualTo(NotificationStatus.FAILED);
    assertThat(row(id).getDeadLetteredAt()).isNull();
    verify(kafka, times(1)).send(eq(Topics.DEAD_LETTER), eq(eventId), anyString());

    // The next run tries again, and only the dead letter: the delivery is not attempted again.
    elapse(Duration.ofMinutes(10));
    Instant before = dbNow();
    dispatcher.dispatchDue();
    assertThat(row(id).getDeadLetteredAt()).isBetween(before, dbNow());
    verify(kafka, times(2)).send(eq(Topics.DEAD_LETTER), eq(eventId), anyString());
    assertThat(provider.attemptedIds()).hasSize(MAX_ATTEMPTS);

    // Acknowledged once, so it is never published again.
    for (int i = 0; i < 3; i++) {
      elapse(Duration.ofMinutes(10));
      dispatcher.dispatchDue();
    }
    verify(kafka, times(2)).send(eq(Topics.DEAD_LETTER), eq(eventId), anyString());
  }

  @Test
  @DisplayName("BR-08 a dead-letter publish that throws does not break the dispatcher")
  void throwingDeadLetterPublishDoesNotBreakTheDispatcher() {
    UUID failing = seed("handler-a", CaseEvents.eventId(), "CASE_OPENED");
    provider.failAll();
    when(kafka.send(eq(Topics.DEAD_LETTER), anyString(), anyString()))
        .thenThrow(new IllegalStateException("producer is closed"));
    runUntilExhausted();
    provider.reset();

    UUID healthy = seed("handler-b", CaseEvents.eventId(), "CASE_OPENED");
    dispatcher.dispatchDue();

    assertThat(row(failing).getDeadLetteredAt()).isNull();
    assertThat(row(healthy).getStatus()).isEqualTo(NotificationStatus.SENT);
  }

  // ------------------------------------------------------------------ leases (ADR 0005, 9)

  @Test
  @DisplayName("BR-08 a claim holds the row for the lease: it is not due again while it is sent")
  void claimHoldsTheRowForTheLease() {
    UUID id = seed("handler-a", CaseEvents.eventId(), "CASE_OPENED");
    List<Instant> dueWhileSending = new ArrayList<>();
    provider.onSend(message -> dueWhileSending.add(row(id).getNextAttemptAt()));

    Instant before = dbNow();
    dispatcher.dispatchDue();
    Instant after = dbNow();

    // The lease ends LEASE after the database's now at the claim.
    assertThat(dueWhileSending)
        .singleElement()
        .satisfies(due -> assertThat(due).isBetween(before.plus(LEASE), after.plus(LEASE)));
    assertThat(provider.sentIds()).containsExactly(id);
  }

  @Test
  @DisplayName(
      "BR-08 a claim lost by a crashed replica is sent again after its lease, on the same row")
  void crashedClaimIsRetriedAfterTheLease() {
    String eventId = CaseEvents.eventId();
    UUID id = seed("handler-a", eventId, "CASE_OPENED");
    // A replica claimed it (attempt 1, lease to now + 60 s) and died before the finish.
    sql.update(
        "update notification.notification set attempts = 1, version = version + 1,"
            + " next_attempt_at = now() + interval '60 seconds' where id = ?",
        id);

    assertThat(dispatcher.dispatchDue()).isZero();
    elapse(LEASE);
    assertThat(dispatcher.dispatchDue()).isEqualTo(1);

    assertThat(row(id).getStatus()).isEqualTo(NotificationStatus.SENT);
    assertThat(row(id).getAttempts()).isEqualTo(2);
    assertThat(rowsOf(eventId)).hasSize(1);
  }

  @Test
  @DisplayName(
      "BR-08 a last attempt whose lease expired without a result is FAILED as LEASE_EXPIRED")
  void expiredLastLeaseIsFailed() {
    String eventId = CaseEvents.eventId();
    UUID id = seed("handler-a", eventId, "CASE_OPENED");
    sql.update(
        "update notification.notification set attempts = ?, version = version + 1,"
            + " next_attempt_at = now() + interval '60 seconds' where id = ?",
        MAX_ATTEMPTS,
        id);
    elapse(LEASE);
    Instant before = dbNow();

    assertThat(dispatcher.dispatchDue()).isZero();

    NotificationEntity failed = row(id);
    Instant after = dbNow();
    assertThat(failed.getStatus()).isEqualTo(NotificationStatus.FAILED);
    assertThat(failed.getLastError()).isEqualTo("LEASE_EXPIRED");
    assertThat(failed.getFailedAt()).isBetween(before, after);
    assertThat(failed.getDeadLetteredAt()).isBetween(before, after);
    assertThat(provider.attemptedIds()).isEmpty();
    verify(kafka, times(1)).send(eq(Topics.DEAD_LETTER), eq(eventId), anyString());
  }

  @Test
  @DisplayName("BR-08 a result whose lease another replica took over is dropped, not recorded")
  void resultOfALostLeaseIsDropped() {
    UUID id = seed("handler-a", CaseEvents.eventId(), "CASE_OPENED");
    // While this replica is in the provider, its lease ends and another replica claims the row.
    provider.onSend(
        message ->
            sql.update(
                "update notification.notification set version = version + 1 where id = ?", id));

    dispatcher.dispatchDue();

    NotificationEntity stored = row(id);
    assertThat(stored.getStatus()).isEqualTo(NotificationStatus.PENDING);
    assertThat(stored.getSentAt()).isNull();
  }

  @Test
  @DisplayName("BR-08 the failure is stored as an error code, never as a provider message")
  void failureIsStoredAsACode() {
    UUID id = seed("handler-a", CaseEvents.eventId(), "CASE_OPENED");
    provider.failAll();

    dispatcher.dispatchDue();

    assertThat(row(id).getLastError()).isEqualTo(ProviderConstants.MOCK_FAILURE);
  }

  @Test
  @DisplayName("BR-08 the dead letter carries the stored correlation id and the failure time")
  void deadLetterCarriesTheCorrelationId() {
    String eventId = CaseEvents.eventId();
    UUID id = seed("handler-a", eventId, "CASE_OPENED");
    provider.failAll();

    runUntilExhausted();

    ArgumentCaptor<String> value = ArgumentCaptor.forClass(String.class);
    verify(kafka).send(eq(Topics.DEAD_LETTER), eq(eventId), value.capture());
    EventEnvelope<DeadLetterPayload> envelope =
        EventJson.read(value.getValue(), DeadLetterPayload.class);
    assertThat(envelope.correlationId()).isEqualTo(CaseEvents.CORRELATION_ID);
    assertThat(envelope.aggregateId()).isEqualTo(eventId);
    assertThat(envelope.schemaVersion()).isEqualTo(1);
    // occurredAt is when the last attempt failed: the stored failed_at, the database's time.
    assertThat(envelope.occurredAt()).isEqualTo(row(id).getFailedAt());
  }

  // ------------------------------------------------------------------ concurrency

  @Test
  @DisplayName("BR-08 a second dispatcher running while the first is sending never sends its rows")
  void secondDispatcherSkipsRowsTheFirstHolds() throws Exception {
    int rows = 20;
    List<UUID> ids = new ArrayList<>();
    for (int i = 0; i < rows; i++) {
      ids.add(seed("handler-" + i, CaseEvents.eventId(), "CASE_OPENED"));
    }
    CountDownLatch firstIsSending = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    AtomicBoolean first = new AtomicBoolean(true);
    provider.onSend(
        message -> {
          if (first.compareAndSet(true, false)) {
            firstIsSending.countDown();
            await(release);
          }
        });
    ExecutorService pool = Executors.newSingleThreadExecutor();
    try {
      Future<Integer> firstRun = pool.submit(dispatcher::dispatchDue);
      assertThat(firstIsSending.await(30, TimeUnit.SECONDS)).isTrue();

      // The first dispatcher is stuck inside the provider and holds its rows.
      int secondRun = dispatcher.dispatchDue();
      release.countDown();
      int processed = secondRun + firstRun.get(30, TimeUnit.SECONDS);

      assertThat(processed).isEqualTo(rows);
      assertThat(provider.attemptedIds()).hasSize(rows).doesNotHaveDuplicates();
      assertThat(provider.attemptedIds()).containsExactlyInAnyOrderElementsOf(ids);
    } finally {
      release.countDown();
      pool.shutdownNow();
    }
  }

  @Test
  @DisplayName("BR-08 two dispatchers started together send every row exactly once")
  void twoDispatchersNeverSendTheSameRow() throws Exception {
    int rows = 40;
    List<UUID> ids = new ArrayList<>();
    for (int i = 0; i < rows; i++) {
      ids.add(seed("handler-" + i, CaseEvents.eventId(), "CASE_OPENED"));
    }
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch go = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      List<Future<Integer>> runs = new ArrayList<>();
      for (int i = 0; i < 2; i++) {
        runs.add(
            pool.submit(
                () -> {
                  ready.countDown();
                  go.await();
                  return dispatcher.dispatchDue();
                }));
      }
      assertThat(ready.await(30, TimeUnit.SECONDS)).isTrue();
      go.countDown();
      int processed = 0;
      for (Future<Integer> run : runs) {
        processed += run.get(60, TimeUnit.SECONDS);
      }

      assertThat(processed).isEqualTo(rows);
      assertThat(new HashSet<>(provider.attemptedIds())).hasSize(rows);
      assertThat(provider.attemptedIds()).hasSize(rows);
      assertThat(provider.attemptedIds()).containsExactlyInAnyOrderElementsOf(ids);
      assertThat(notifications.findAll())
          .allSatisfy(n -> assertThat(n.getStatus()).isEqualTo(NotificationStatus.SENT));
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  @DisplayName(
      "BR-08 a shutdown during the last attempt gives the attempt back and does not fail it")
  void shutdownDuringTheLastAttemptDoesNotFailTheRow() throws Exception {
    UUID id = seed("handler-a", CaseEvents.eventId(), "CASE_OPENED");
    sql.update(
        "update notification.notification set attempts = ?, version = version + 1 where id = ?",
        MAX_ATTEMPTS - 1,
        id);
    DeliveryDispatcher stopping =
        new DeliveryDispatcher(
            deliveries, provider, deadLetterPublisher, backoff, properties, meters);
    CountDownLatch sending = new CountDownLatch(1);
    CountDownLatch never = new CountDownLatch(1);
    provider.onSend(
        message -> {
          sending.countDown();
          try {
            never.await(60, TimeUnit.SECONDS);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("cut short by the shutdown");
          }
        });
    ExecutorService pool = Executors.newSingleThreadExecutor();
    try {
      Future<Integer> run = pool.submit(stopping::sendDue);
      assertThat(sending.await(30, TimeUnit.SECONDS)).isTrue();

      stopping.stop();
      run.get(30, TimeUnit.SECONDS);

      NotificationEntity row = row(id);
      assertThat(row.getStatus()).isEqualTo(NotificationStatus.PENDING);
      assertThat(row.getAttempts()).isEqualTo(MAX_ATTEMPTS - 1);
      assertThat(stopping.sendDue()).isZero();

      // The next replica sends it as its last attempt, and it works.
      provider.onSend(null);
      dispatcher.dispatchDue();
      assertThat(row(id).getStatus()).isEqualTo(NotificationStatus.SENT);
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  @DisplayName("BR-08 an interrupted dispatcher thread releases the row with its flag cleared")
  void interruptedThreadReleasesWithTheFlagCleared() throws Exception {
    UUID id = seed("handler-a", CaseEvents.eventId(), "CASE_OPENED");
    DeliveryDispatcher interrupted =
        new DeliveryDispatcher(
            deliveries, provider, deadLetterPublisher, backoff, properties, meters);
    AtomicBoolean flagDuringRelease = new AtomicBoolean(true);
    doAnswer(
            invocation -> {
              flagDuringRelease.set(Thread.currentThread().isInterrupted());
              return 1;
            })
        .when(deliveries)
        .release(eq(id), anyLong());
    CountDownLatch sending = new CountDownLatch(1);
    CountDownLatch freeTheProvider = new CountDownLatch(1);
    provider.onSend(
        message -> {
          sending.countDown();
          await(freeTheProvider);
        });
    AtomicReference<Thread> dispatcherThread = new AtomicReference<>();
    ExecutorService pool = Executors.newSingleThreadExecutor();
    try {
      Future<Boolean> run =
          pool.submit(
              () -> {
                dispatcherThread.set(Thread.currentThread());
                interrupted.sendDue();
                return Thread.currentThread().isInterrupted();
              });
      assertThat(sending.await(30, TimeUnit.SECONDS)).isTrue();

      dispatcherThread.get().interrupt();
      boolean flagAfter = run.get(30, TimeUnit.SECONDS);

      // The write ran with the flag cleared, and the flag is set again for the caller.
      assertThat(flagDuringRelease.get()).isFalse();
      assertThat(flagAfter).isTrue();
    } finally {
      freeTheProvider.countDown();
      pool.shutdownNow();
    }
  }

  @Test
  @DisplayName(
      "BR-08 a shutdown between the claim and the sends releases the claimed rows, none is lost")
  void shutdownAfterTheClaimReleasesTheRows() {
    UUID id = seed("handler-a", CaseEvents.eventId(), "CASE_OPENED");
    sql.update(
        "update notification.notification set attempts = ?, version = version + 1 where id = ?",
        MAX_ATTEMPTS - 1,
        id);
    DeliveryDispatcher stopping =
        new DeliveryDispatcher(
            deliveries, provider, deadLetterPublisher, backoff, properties, meters);
    // The shutdown lands right after the claim, before the sends are submitted.
    doAnswer(
            invocation -> {
              Object claims = invocation.callRealMethod();
              stopping.stop();
              return claims;
            })
        .when(deliveries)
        .claimDue(any(), anyInt(), anyInt());

    stopping.sendDue();

    NotificationEntity row = row(id);
    assertThat(row.getStatus()).isEqualTo(NotificationStatus.PENDING);
    assertThat(row.getAttempts()).isEqualTo(MAX_ATTEMPTS - 1);
    assertThat(provider.attemptedIds()).isEmpty();

    // Due at once: the next replica sends it as its last attempt.
    dispatcher.dispatchDue();
    assertThat(row(id).getStatus()).isEqualTo(NotificationStatus.SENT);
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(60, TimeUnit.SECONDS)) {
        throw new AssertionError("the test never released the provider");
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }
}
