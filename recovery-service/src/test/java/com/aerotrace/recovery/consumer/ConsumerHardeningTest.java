package com.aerotrace.recovery.consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aerotrace.common.event.CapacityChangedPayload;
import com.aerotrace.common.event.EventEnvelope;
import com.aerotrace.common.event.EventJson;
import com.aerotrace.common.event.RiskChangedPayload;
import com.aerotrace.common.event.ScanAnomalyPayload;
import com.aerotrace.common.model.AnomalyType;
import com.aerotrace.common.model.RecoveryState;
import com.aerotrace.common.model.RiskLevel;
import com.aerotrace.recovery.base.AbstractRecoveryTest;
import com.aerotrace.recovery.model.dto.request.TransitionRequest;
import com.aerotrace.recovery.model.entity.RecoveryCaseEntity;
import com.aerotrace.recovery.repository.ProcessedEventRepository;
import com.aerotrace.recovery.repository.RecoveryCaseRepository;
import com.aerotrace.recovery.service.RecoveryCaseService;
import com.aerotrace.recovery.strategy.RecoveryAction;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/** Edge cases found in review: stale events, dedupe keys, size limits, purging, plans in flight. */
@SpringBootTest
class ConsumerHardeningTest extends AbstractRecoveryTest {

  @Autowired RiskEventHandler risk;
  @Autowired ScanAnomalyHandler anomaly;
  @Autowired CapacityEventHandler capacity;
  @Autowired ProcessedEventPurger purger;
  @Autowired ProcessedEventRepository processed;
  @Autowired RecoveryCaseRepository cases;
  @Autowired RecoveryCaseService service;
  @Autowired org.springframework.transaction.PlatformTransactionManager transactionManager;

  // ------------------------------------------------------------------ helpers

  private static String bagTag() {
    return String.format("%010d", ThreadLocalRandom.current().nextLong(10_000_000_000L));
  }

  private static String riskEvent(
      String eventId, String bag, RiskLevel current, Instant occurredAt) {
    return EventJson.write(
        new EventEnvelope<>(
            eventId,
            "baggage.risk.changed",
            1,
            bag + ":LEG-2",
            occurredAt,
            Instant.now(),
            "corr-1",
            new RiskChangedPayload(
                bag, "LEG-2", RiskLevel.LOW, current, Instant.now().plus(15, ChronoUnit.MINUTES))));
  }

  private static String anomalyEvent(String eventId, String bag) {
    Instant start = Instant.now().minus(5, ChronoUnit.MINUTES);
    return EventJson.write(
        new EventEnvelope<>(
            eventId,
            "baggage.scan.anomaly",
            1,
            bag,
            Instant.now(),
            Instant.now(),
            "corr-1",
            new ScanAnomalyPayload(
                bag, AnomalyType.SCANNER_ANOMALY, 3, start, start.plus(5, ChronoUnit.MINUTES))));
  }

  private List<RecoveryCaseEntity> casesOf(String bag) {
    return cases.findAll().stream().filter(c -> c.getBagTag().equals(bag)).toList();
  }

  // ------------------------------------------------------------------ stale risk events

  @Test
  @DisplayName("AT-08 a late HIGH after the LOW that cleared it opens no false case")
  void lateHighAfterTheClearingLowIsStale() {
    String bag = bagTag();
    Instant t0 = Instant.now().minusSeconds(120);
    risk.handle(riskEvent("EV-" + UUID.randomUUID(), bag, RiskLevel.HIGH, t0));
    risk.handle(riskEvent("EV-" + UUID.randomUUID(), bag, RiskLevel.LOW, t0.plusSeconds(60)));
    assertThat(casesOf(bag))
        .singleElement()
        .satisfies(c -> assertThat(c.getState()).isEqualTo(RecoveryState.CANCELLED));

    // A replayed HIGH from before the LOW, under a new event id.
    RiskEventHandler.Outcome outcome =
        risk.handle(riskEvent("EV-" + UUID.randomUUID(), bag, RiskLevel.HIGH, t0.plusSeconds(30)));

    assertThat(outcome).isEqualTo(RiskEventHandler.Outcome.STALE_EVENT);
    assertThat(casesOf(bag)).hasSize(1);
  }

  @Test
  @DisplayName("AT-08 a newer HIGH after the clearing LOW opens a new case")
  void newerHighAfterTheClearingLowOpensANewCase() {
    String bag = bagTag();
    Instant t0 = Instant.now().minusSeconds(120);
    risk.handle(riskEvent("EV-" + UUID.randomUUID(), bag, RiskLevel.HIGH, t0));
    risk.handle(riskEvent("EV-" + UUID.randomUUID(), bag, RiskLevel.LOW, t0.plusSeconds(30)));

    RiskEventHandler.Outcome outcome =
        risk.handle(riskEvent("EV-" + UUID.randomUUID(), bag, RiskLevel.HIGH, t0.plusSeconds(60)));

    assertThat(outcome).isEqualTo(RiskEventHandler.Outcome.CASE_OPENED);
    assertThat(casesOf(bag)).hasSize(2);
  }

  // ------------------------------------------------------------------ dedupe keys

  @Test
  @DisplayName("AT-03 the same event id on another topic is not a duplicate")
  void sameEventIdOnAnotherTopicIsNotADuplicate() {
    String bag = bagTag();
    String sharedId = "EV-" + UUID.randomUUID();
    risk.handle(riskEvent(sharedId, bag, RiskLevel.HIGH, Instant.now()));

    ScanAnomalyHandler.Outcome outcome = anomaly.handle(anomalyEvent(sharedId, bag));

    assertThat(outcome).isEqualTo(ScanAnomalyHandler.Outcome.CASES_FLAGGED);
  }

  @Test
  @DisplayName("Poison an event id that is too long to be remembered is refused")
  void tooLongEventIdIsPoison() {
    String json = riskEvent("E".repeat(101), bagTag(), RiskLevel.HIGH, Instant.now());

    assertThatThrownBy(() -> risk.handle(json))
        .isInstanceOf(PoisonEventException.class)
        .hasMessage("eventId is missing or too long");
  }

  // ------------------------------------------------------------------ poison and limits

  @Test
  @DisplayName("Poison an event without a payload is refused for good, not retried")
  void eventWithoutPayloadIsPoison() {
    String json =
        riskEvent("EV-" + UUID.randomUUID(), bagTag(), RiskLevel.HIGH, Instant.now())
            .replaceAll("\"payload\":\\{.*}", "\"payload\":null}");

    assertThatThrownBy(() -> risk.handle(json)).isInstanceOf(PoisonEventException.class);
  }

  @Test
  @DisplayName(
      "BR-04 a capacity event dated far in the future is refused, so it cannot freeze a resource")
  void futureCapacityEventIsPoison() {
    String json =
        EventJson.write(
            new EventEnvelope<>(
                "EV-" + UUID.randomUUID(),
                "recovery.capacity.changed",
                1,
                "CART-1",
                Instant.now().plus(2, ChronoUnit.DAYS),
                Instant.now(),
                "corr-1",
                new CapacityChangedPayload("CART-1", 10, 5)));

    assertThatThrownBy(() -> capacity.handle(json))
        .isInstanceOf(PoisonEventException.class)
        .hasMessage("occurredAt is in the future");
  }

  // ------------------------------------------------------------------ purge

  @Test
  @DisplayName("Dedupe old processed event ids are forgotten, recent ones kept")
  void purgeForgetsOnlyOldIds() {
    String old = "purge-old-" + UUID.randomUUID();
    String recent = "purge-recent-" + UUID.randomUUID();
    new org.springframework.transaction.support.TransactionTemplate(transactionManager)
        .executeWithoutResult(
            status -> {
              processed.insertIfAbsent(old, Instant.now().minus(30, ChronoUnit.DAYS));
              processed.insertIfAbsent(recent, Instant.now().minus(1, ChronoUnit.DAYS));
            });

    int removed = purger.purge();

    assertThat(removed).isGreaterThanOrEqualTo(1);
    assertThat(processed.existsById(old)).isFalse();
    assertThat(processed.existsById(recent)).isTrue();
  }

  // ------------------------------------------------------------------ a plan already in flight

  @Test
  @DisplayName("AT-06 an anomaly on a reserved case flags it but keeps its plan")
  void anomalyKeepsThePlanOfAReservedCase() {
    String bag = bagTag();
    risk.handle(riskEvent("EV-" + UUID.randomUUID(), bag, RiskLevel.HIGH, Instant.now()));
    RecoveryCaseEntity opened = casesOf(bag).getFirst();
    assertThat(opened.getPlannedAction()).isEqualTo(RecoveryAction.EXPEDITED_TRANSFER);
    service.transition(
        opened.getId(), new TransitionRequest(RecoveryState.ASSESSING, 1L, "Agent picked it up"));
    service.transition(
        opened.getId(), new TransitionRequest(RecoveryState.RESERVED, 2L, "Slot secured"));

    anomaly.handle(anomalyEvent("EV-" + UUID.randomUUID(), bag));

    RecoveryCaseEntity after = cases.findById(opened.getId()).orElseThrow();
    assertThat(after.getScannerAnomalyAt()).isNotNull();
    assertThat(after.getState()).isEqualTo(RecoveryState.RESERVED);
    assertThat(after.getPlannedAction()).isEqualTo(RecoveryAction.EXPEDITED_TRANSFER);
    assertThat(after.getVersion()).isEqualTo(4);
  }
}
