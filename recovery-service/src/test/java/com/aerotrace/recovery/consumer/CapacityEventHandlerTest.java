package com.aerotrace.recovery.consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aerotrace.common.event.CapacityChangedPayload;
import com.aerotrace.common.event.EventEnvelope;
import com.aerotrace.common.event.EventJson;
import com.aerotrace.common.event.RiskChangedPayload;
import com.aerotrace.common.model.RiskLevel;
import com.aerotrace.recovery.base.AbstractRecoveryTest;
import com.aerotrace.recovery.consumer.CapacityEventHandler.Outcome;
import com.aerotrace.recovery.model.entity.CapacitySnapshotEntity;
import com.aerotrace.recovery.repository.CapacitySnapshotRepository;
import com.aerotrace.recovery.repository.RecoveryCaseRepository;
import com.aerotrace.recovery.service.CapacityView;
import com.aerotrace.recovery.strategy.RecoveryAction;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/** The capacity event handler against a real PostgreSQL. */
@SpringBootTest
class CapacityEventHandlerTest extends AbstractRecoveryTest {

  @Autowired CapacityEventHandler handler;
  @Autowired RiskEventHandler riskHandler;
  @Autowired CapacitySnapshotRepository snapshots;
  @Autowired RecoveryCaseRepository cases;
  @Autowired CapacityView view;

  /** Snapshots are global, so each test starts and ends with none. */
  @BeforeEach
  @AfterEach
  void noSnapshots() {
    snapshots.deleteAll();
  }

  // ------------------------------------------------------------------ helpers

  private static String capacity(
      String eventId, String resourceId, int total, int remaining, Instant occurredAt) {
    return EventJson.write(
        new EventEnvelope<>(
            eventId,
            "recovery.capacity.changed",
            1,
            resourceId,
            occurredAt,
            Instant.now(),
            "corr-1",
            new CapacityChangedPayload(resourceId, total, remaining)));
  }

  private static String capacity(String resourceId, int total, int remaining, Instant at) {
    return capacity("EV-" + UUID.randomUUID(), resourceId, total, remaining, at);
  }

  private CapacitySnapshotEntity snapshot(String resourceId) {
    return snapshots.findById(resourceId).orElseThrow();
  }

  // ------------------------------------------------------------------ snapshots

  @Test
  @DisplayName("BR-04 a capacity event stores the slots of the resource")
  void capacityEventStoresTheSlots() {
    Outcome outcome = handler.handle(capacity("CART-1", 10, 7, Instant.now()));

    assertThat(outcome).isEqualTo(Outcome.SNAPSHOT_UPDATED);
    assertThat(snapshot("CART-1").getTotalSlots()).isEqualTo(10);
    assertThat(snapshot("CART-1").getRemainingSlots()).isEqualTo(7);
  }

  @Test
  @DisplayName("BR-04 a newer event replaces the snapshot")
  void newerEventReplacesTheSnapshot() {
    Instant now = Instant.now();
    handler.handle(capacity("CART-1", 10, 7, now.minusSeconds(60)));

    Outcome outcome = handler.handle(capacity("CART-1", 10, 3, now));

    assertThat(outcome).isEqualTo(Outcome.SNAPSHOT_UPDATED);
    assertThat(snapshot("CART-1").getRemainingSlots()).isEqualTo(3);
  }

  @Test
  @DisplayName("AT-04 an older event that arrives late never overwrites a newer snapshot")
  void staleEventDoesNotOverwrite() {
    Instant now = Instant.now();
    handler.handle(capacity("CART-1", 10, 3, now));

    Outcome outcome = handler.handle(capacity("CART-1", 10, 9, now.minusSeconds(60)));

    assertThat(outcome).isEqualTo(Outcome.STALE_EVENT);
    assertThat(snapshot("CART-1").getRemainingSlots()).isEqualTo(3);
  }

  @Test
  @DisplayName("AT-03 the same capacity event twice has one effect")
  void sameEventTwiceHasOneEffect() {
    String json = capacity("EV-" + UUID.randomUUID(), "CART-1", 10, 5, Instant.now());

    Outcome first = handler.handle(json);
    Outcome second = handler.handle(json);

    assertThat(first).isEqualTo(Outcome.SNAPSHOT_UPDATED);
    assertThat(second).isEqualTo(Outcome.DUPLICATE);
    assertThat(snapshots.count()).isEqualTo(1);
  }

  @Test
  @DisplayName("BR-04 resources are kept apart")
  void resourcesAreKeptApart() {
    handler.handle(capacity("CART-1", 10, 0, Instant.now()));
    handler.handle(capacity("CART-2", 4, 4, Instant.now()));

    assertThat(snapshot("CART-1").getRemainingSlots()).isZero();
    assertThat(snapshot("CART-2").getRemainingSlots()).isEqualTo(4);
  }

  // ------------------------------------------------------------------ the view and the strategy

  @Test
  @DisplayName("Strategy before any capacity event the view is optimistic")
  void viewIsOptimisticWithoutSnapshots() {
    assertThat(view.available()).isTrue();
  }

  @Test
  @DisplayName("Strategy the view is false only when every known resource is full")
  void viewFollowsTheSnapshots() {
    handler.handle(capacity("CART-1", 10, 0, Instant.now()));
    assertThat(view.available()).isFalse();

    handler.handle(capacity("CART-2", 4, 1, Instant.now()));
    assertThat(view.available()).isTrue();
  }

  @Test
  @DisplayName("Strategy no free capacity plans the service desk, free capacity plans a transfer")
  void plannedActionFollowsTheCapacity() {
    handler.handle(capacity("CART-1", 10, 0, Instant.now()));
    String full = bagTag();
    riskHandler.handle(highRisk(full));

    handler.handle(capacity("CART-1", 10, 2, Instant.now().plusSeconds(5)));
    String free = bagTag();
    riskHandler.handle(highRisk(free));

    assertThat(plannedActionOf(full)).isEqualTo(RecoveryAction.HOLD_AT_SERVICE_DESK);
    assertThat(plannedActionOf(free)).isEqualTo(RecoveryAction.EXPEDITED_TRANSFER);
  }

  private static String bagTag() {
    return String.format("%010d", ThreadLocalRandom.current().nextLong(10_000_000_000L));
  }

  private static String highRisk(String bagTag) {
    return EventJson.write(
        new EventEnvelope<>(
            "EV-" + UUID.randomUUID(),
            "baggage.risk.changed",
            1,
            bagTag + ":LEG-2",
            Instant.now(),
            Instant.now(),
            "corr-1",
            new RiskChangedPayload(
                bagTag,
                "LEG-2",
                RiskLevel.LOW,
                RiskLevel.HIGH,
                Instant.now().plus(15, ChronoUnit.MINUTES))));
  }

  private RecoveryAction plannedActionOf(String bagTag) {
    return cases.findAll().stream()
        .filter(c -> c.getBagTag().equals(bagTag))
        .findFirst()
        .orElseThrow()
        .getPlannedAction();
  }

  // ------------------------------------------------------------------ poison events

  @Test
  @DisplayName("Poison inconsistent slot counts or a bad resource id are refused for good")
  void inconsistentCapacityIsPoison() {
    assertThatThrownBy(() -> handler.handle(capacity("CART-1", 5, 6, Instant.now())))
        .isInstanceOf(PoisonEventException.class)
        .hasMessage("slot counts are inconsistent");
    assertThatThrownBy(() -> handler.handle(capacity("CART-1", 5, -1, Instant.now())))
        .isInstanceOf(PoisonEventException.class);
    assertThatThrownBy(() -> handler.handle(capacity("x".repeat(65), 5, 1, Instant.now())))
        .isInstanceOf(PoisonEventException.class)
        .hasMessage("resourceId is missing or too long");
    assertThat(snapshots.count()).isZero();
  }

  @Test
  @DisplayName("Poison an event of another type or with no JSON is refused")
  void otherTypesArePoison() {
    assertThatThrownBy(
            () ->
                handler.handle(
                    capacity("CART-1", 5, 1, Instant.now())
                        .replace("recovery.capacity.changed", "other")))
        .isInstanceOf(PoisonEventException.class)
        .hasMessage("unexpected event type");
    assertThatThrownBy(() -> handler.handle("nope")).isInstanceOf(PoisonEventException.class);
  }
}
