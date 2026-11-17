package com.aerotrace.recovery.consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aerotrace.common.event.EventEnvelope;
import com.aerotrace.common.event.EventJson;
import com.aerotrace.common.event.ScanAnomalyPayload;
import com.aerotrace.common.model.AnomalyType;
import com.aerotrace.common.model.RecoveryState;
import com.aerotrace.recovery.base.AbstractRecoveryTest;
import com.aerotrace.recovery.consumer.ScanAnomalyHandler.Outcome;
import com.aerotrace.recovery.model.dto.request.TransitionRequest;
import com.aerotrace.recovery.model.entity.RecoveryCaseEntity;
import com.aerotrace.recovery.repository.OutboxEventRepository;
import com.aerotrace.recovery.repository.RecoveryCaseRepository;
import com.aerotrace.recovery.repository.StateTransitionRepository;
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

/**
 * The scanner anomaly handler against a real PostgreSQL; no Kafka is needed to prove the effects.
 */
@SpringBootTest
class ScanAnomalyHandlerTest extends AbstractRecoveryTest {

  @Autowired ScanAnomalyHandler handler;
  @Autowired RiskEventHandler riskHandler;
  @Autowired RecoveryCaseRepository cases;
  @Autowired StateTransitionRepository transitions;
  @Autowired OutboxEventRepository outbox;
  @Autowired RecoveryCaseService service;

  // ------------------------------------------------------------------ helpers

  private static String bagTag() {
    return String.format("%010d", ThreadLocalRandom.current().nextLong(10_000_000_000L));
  }

  private static String anomaly(String eventId, String bagTag, AnomalyType type) {
    Instant start = Instant.now().minus(5, ChronoUnit.MINUTES);
    return EventJson.write(
        new EventEnvelope<>(
            eventId,
            "baggage.scan.anomaly",
            1,
            bagTag,
            Instant.now(),
            Instant.now(),
            "corr-1",
            new ScanAnomalyPayload(bagTag, type, 3, start, start.plus(5, ChronoUnit.MINUTES))));
  }

  private static String scannerAnomaly(String bagTag) {
    return anomaly("EV-" + UUID.randomUUID(), bagTag, AnomalyType.SCANNER_ANOMALY);
  }

  /** Opens a case for the bag through a risk event, 15 minutes before departure. */
  private RecoveryCaseEntity caseOf(String bagTag, String legId) {
    String risk =
        EventJson.write(
            new EventEnvelope<>(
                "EV-" + UUID.randomUUID(),
                "baggage.risk.changed",
                1,
                bagTag + ":" + legId,
                Instant.now(),
                Instant.now(),
                "corr-1",
                new com.aerotrace.common.event.RiskChangedPayload(
                    bagTag,
                    legId,
                    com.aerotrace.common.model.RiskLevel.LOW,
                    com.aerotrace.common.model.RiskLevel.HIGH,
                    Instant.now().plus(15, ChronoUnit.MINUTES))));
    riskHandler.handle(risk);
    return casesOf(bagTag).stream()
        .filter(c -> c.getLegId().equals(legId))
        .findFirst()
        .orElseThrow();
  }

  private List<RecoveryCaseEntity> casesOf(String bagTag) {
    return cases.findAll().stream().filter(c -> c.getBagTag().equals(bagTag)).toList();
  }

  // ------------------------------------------------------------------ BR-03 effects

  @Test
  @DisplayName("AT-06 a scanner anomaly flags the active case and plans the service desk")
  void anomalyFlagsTheActiveCase() {
    String bag = bagTag();
    RecoveryCaseEntity before = caseOf(bag, "LEG-2");
    assertThat(before.getPlannedAction()).isEqualTo(RecoveryAction.EXPEDITED_TRANSFER);

    Outcome outcome = handler.handle(scannerAnomaly(bag));

    assertThat(outcome).isEqualTo(Outcome.CASES_FLAGGED);
    RecoveryCaseEntity after = cases.findById(before.getId()).orElseThrow();
    assertThat(after.getScannerAnomalyAt()).isNotNull();
    assertThat(after.getPlannedAction()).isEqualTo(RecoveryAction.HOLD_AT_SERVICE_DESK);
    assertThat(after.getState()).isEqualTo(RecoveryState.OPEN);
    // The version rises, so an agent holding the old version must reload before moving the case.
    assertThat(after.getVersion()).isEqualTo(before.getVersion() + 1);
  }

  @Test
  @DisplayName("AT-06 the flag is recorded in the case history and an event is queued")
  void flagIsRecordedAndPublished() {
    String bag = bagTag();
    RecoveryCaseEntity before = caseOf(bag, "LEG-2");

    handler.handle(scannerAnomaly(bag));

    var last = transitions.findByCaseIdOrderByVersionAfterAsc(before.getId()).getLast();
    assertThat(last.getFromState()).isEqualTo(RecoveryState.OPEN);
    assertThat(last.getToState()).isEqualTo(RecoveryState.OPEN);
    assertThat(last.getActor()).isEqualTo("system");
    assertThat(last.getReason()).startsWith("SCANNER_ANOMALY");
    assertThat(last.getVersionAfter()).isEqualTo(2);
    assertThat(outbox.findByMessageKeyOrderByCreatedAt(before.getId().toString())).hasSize(2);
  }

  @Test
  @DisplayName("AT-06 every active case of the bag is flagged, other bags are not")
  void everyCaseOfTheBagIsFlagged() {
    String bag = bagTag();
    String other = bagTag();
    caseOf(bag, "LEG-1");
    caseOf(bag, "LEG-2");
    RecoveryCaseEntity otherCase = caseOf(other, "LEG-2");

    handler.handle(scannerAnomaly(bag));

    assertThat(casesOf(bag)).allSatisfy(c -> assertThat(c.getScannerAnomalyAt()).isNotNull());
    assertThat(cases.findById(otherCase.getId()).orElseThrow().getScannerAnomalyAt()).isNull();
  }

  @Test
  @DisplayName("AT-03 the same anomaly event twice flags once")
  void sameEventTwiceFlagsOnce() {
    String bag = bagTag();
    RecoveryCaseEntity c = caseOf(bag, "LEG-2");
    String json = scannerAnomaly(bag);

    Outcome first = handler.handle(json);
    Outcome second = handler.handle(json);

    assertThat(first).isEqualTo(Outcome.CASES_FLAGGED);
    assertThat(second).isEqualTo(Outcome.DUPLICATE);
    assertThat(cases.findById(c.getId()).orElseThrow().getVersion()).isEqualTo(2);
  }

  @Test
  @DisplayName("AT-06 a second anomaly event for an already flagged case changes nothing")
  void alreadyFlaggedCaseIsNotFlaggedAgain() {
    String bag = bagTag();
    RecoveryCaseEntity c = caseOf(bag, "LEG-2");
    handler.handle(scannerAnomaly(bag));

    Outcome again = handler.handle(scannerAnomaly(bag));

    assertThat(again).isEqualTo(Outcome.NO_CASE_TO_FLAG);
    assertThat(cases.findById(c.getId()).orElseThrow().getVersion()).isEqualTo(2);
  }

  @Test
  @DisplayName("AT-06 an anomaly on a bag without a case creates no case")
  void anomalyWithoutACaseCreatesNothing() {
    String bag = bagTag();

    Outcome outcome = handler.handle(scannerAnomaly(bag));

    assertThat(outcome).isEqualTo(Outcome.NO_CASE_TO_FLAG);
    assertThat(casesOf(bag)).isEmpty();
  }

  @Test
  @DisplayName("BR-07 a final case is not flagged")
  void finalCaseIsNotFlagged() {
    String bag = bagTag();
    RecoveryCaseEntity c = caseOf(bag, "LEG-2");
    service.transition(
        c.getId(), new TransitionRequest(RecoveryState.CANCELLED, 1L, "No longer needed"));

    Outcome outcome = handler.handle(scannerAnomaly(bag));

    assertThat(outcome).isEqualTo(Outcome.NO_CASE_TO_FLAG);
    assertThat(cases.findById(c.getId()).orElseThrow().getScannerAnomalyAt()).isNull();
  }

  @Test
  @DisplayName("BR-06 a LATE_EVENT anomaly is only an audit flag: no case is touched")
  void lateEventAnomalyIsIgnored() {
    String bag = bagTag();
    RecoveryCaseEntity c = caseOf(bag, "LEG-2");

    Outcome outcome =
        handler.handle(anomaly("EV-" + UUID.randomUUID(), bag, AnomalyType.LATE_EVENT));

    assertThat(outcome).isEqualTo(Outcome.IGNORED_TYPE);
    assertThat(cases.findById(c.getId()).orElseThrow().getScannerAnomalyAt()).isNull();
  }

  @Test
  @DisplayName("Poison an anomaly type this version does not know is skipped, not dead-lettered")
  void unknownAnomalyTypeIsSkipped() {
    String bag = bagTag();
    String json =
        anomaly("EV-" + UUID.randomUUID(), bag, AnomalyType.SCANNER_ANOMALY)
            .replace("SCANNER_ANOMALY", "FUTURE_ANOMALY");

    assertThat(handler.handle(json)).isEqualTo(Outcome.IGNORED_TYPE);
  }

  // ------------------------------------------------------------------ poison events

  @Test
  @DisplayName("Poison an anomaly with a bad bag tag, a wrong type or no JSON is refused for good")
  void badAnomalyEventsArePoison() {
    assertThatThrownBy(() -> handler.handle(scannerAnomaly("AB12")))
        .isInstanceOf(PoisonEventException.class)
        .hasMessage("bagTag is not a bag tag");
    assertThatThrownBy(
            () -> handler.handle(scannerAnomaly(bagTag()).replace("baggage.scan.anomaly", "other")))
        .isInstanceOf(PoisonEventException.class)
        .hasMessage("unexpected event type");
    assertThatThrownBy(() -> handler.handle("{nope"))
        .isInstanceOf(PoisonEventException.class)
        .hasMessage("event is not readable");
  }
}
