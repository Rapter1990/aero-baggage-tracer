package com.aerotrace.recovery.consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aerotrace.common.event.EventEnvelope;
import com.aerotrace.common.event.EventJson;
import com.aerotrace.common.event.RiskChangedPayload;
import com.aerotrace.common.model.RecoveryState;
import com.aerotrace.common.model.RiskLevel;
import com.aerotrace.recovery.base.AbstractRecoveryTest;
import com.aerotrace.recovery.consumer.RiskEventHandler.Outcome;
import com.aerotrace.recovery.model.dto.request.TransitionRequest;
import com.aerotrace.recovery.model.entity.RecoveryCaseEntity;
import com.aerotrace.recovery.repository.OutboxEventRepository;
import com.aerotrace.recovery.repository.ProcessedEventRepository;
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

/** The risk event handler against a real PostgreSQL; no Kafka is needed to prove the effects. */
@SpringBootTest
class RiskEventHandlerTest extends AbstractRecoveryTest {

  @Autowired RiskEventHandler handler;
  @Autowired RecoveryCaseRepository cases;
  @Autowired ProcessedEventRepository processed;
  @Autowired StateTransitionRepository transitions;
  @Autowired OutboxEventRepository outbox;
  @Autowired RecoveryCaseService service;

  // ------------------------------------------------------------------ helpers

  private static String bagTag() {
    return String.format("%010d", ThreadLocalRandom.current().nextLong(10_000_000_000L));
  }

  private static String eventId() {
    return "EV-" + UUID.randomUUID();
  }

  private static String event(
      String eventId, String bagTag, String legId, RiskLevel current, Instant departureAt) {
    return EventJson.write(
        new EventEnvelope<>(
            eventId,
            "baggage.risk.changed",
            1,
            bagTag + ":" + legId,
            Instant.now(),
            Instant.now(),
            "corr-1",
            new RiskChangedPayload(bagTag, legId, RiskLevel.LOW, current, departureAt)));
  }

  private static String high(String bagTag, String legId, Instant departureAt) {
    return event(eventId(), bagTag, legId, RiskLevel.HIGH, departureAt);
  }

  private List<RecoveryCaseEntity> casesOf(String bagTag) {
    return cases.findAll().stream().filter(c -> c.getBagTag().equals(bagTag)).toList();
  }

  private static Instant in(long minutes) {
    return Instant.now().plus(minutes, ChronoUnit.MINUTES);
  }

  // ------------------------------------------------------------------ AT-07: HIGH opens a case

  @Test
  @DisplayName("AT-07 a HIGH risk event opens one OPEN case, recorded by the system")
  void highRiskOpensACase() {
    String bag = bagTag();

    Outcome outcome = handler.handle(high(bag, "LEG-2", in(15)));

    assertThat(outcome).isEqualTo(Outcome.CASE_OPENED);
    RecoveryCaseEntity c = casesOf(bag).getFirst();
    assertThat(c.getState()).isEqualTo(RecoveryState.OPEN);
    assertThat(c.getVersion()).isEqualTo(1);
    assertThat(c.getReason()).isEqualTo("HIGH_CONNECTION_RISK");
    assertThat(c.getCreatedBy()).isEqualTo("system");
    assertThat(transitions.findByCaseIdOrderByVersionAfterAsc(c.getId())).hasSize(1);
    assertThat(outbox.findByMessageKeyOrderByCreatedAt(c.getId().toString())).hasSize(1);
  }

  @Test
  @DisplayName("A 36 character UUID leg id, as the journey-service issues it, opens a case")
  void uuidLegIdOpensAndClearsACase() {
    String bag = bagTag();
    String legId = UUID.randomUUID().toString();
    assertThat(legId).hasSize(36);

    Outcome outcome = handler.handle(high(bag, legId, in(15)));

    assertThat(outcome).isEqualTo(Outcome.CASE_OPENED);
    assertThat(casesOf(bag).getFirst().getLegId()).isEqualTo(legId);

    Outcome cleared = handler.handle(event(eventId(), bag, legId, RiskLevel.LOW, in(15)));

    assertThat(cleared).isEqualTo(Outcome.CASE_CANCELLED);
  }

  @Test
  @DisplayName("Strategy the action planned on the case follows the time left")
  void plannedActionFollowsTheTimeLeft() {
    String comfortable = bagTag();
    String late = bagTag();
    String unknown = bagTag();

    handler.handle(high(comfortable, "LEG-2", in(15)));
    handler.handle(high(late, "LEG-2", in(2)));
    handler.handle(high(unknown, "LEG-2", null));

    assertThat(casesOf(comfortable).getFirst().getPlannedAction())
        .isEqualTo(RecoveryAction.EXPEDITED_TRANSFER);
    assertThat(casesOf(late).getFirst().getPlannedAction())
        .isEqualTo(RecoveryAction.REROUTE_NEXT_FLIGHT);
    assertThat(casesOf(unknown).getFirst().getPlannedAction())
        .isEqualTo(RecoveryAction.HOLD_AT_SERVICE_DESK);
  }

  // ------------------------------------------------------------------ idempotency, BR-01

  @Test
  @DisplayName("AT-03 the same event twice has one effect")
  void sameEventTwiceHasOneEffect() {
    String bag = bagTag();
    String json = high(bag, "LEG-2", in(15));

    Outcome first = handler.handle(json);
    Outcome second = handler.handle(json);

    assertThat(first).isEqualTo(Outcome.CASE_OPENED);
    assertThat(second).isEqualTo(Outcome.DUPLICATE);
    assertThat(casesOf(bag)).hasSize(1);
  }

  @Test
  @DisplayName("BR-01 a new HIGH event for a leg that has an active case opens no second case")
  void secondEventForTheSameLegOpensNoSecondCase() {
    String bag = bagTag();
    handler.handle(high(bag, "LEG-2", in(15)));

    Outcome again = handler.handle(high(bag, "LEG-2", in(14)));

    assertThat(again).isEqualTo(Outcome.CASE_ALREADY_ACTIVE);
    assertThat(casesOf(bag)).hasSize(1);
  }

  @Test
  @DisplayName("BR-01 another leg of the same bag gets its own case")
  void anotherLegGetsItsOwnCase() {
    String bag = bagTag();

    handler.handle(high(bag, "LEG-1", in(15)));
    handler.handle(high(bag, "LEG-2", in(15)));

    assertThat(casesOf(bag)).hasSize(2);
  }

  // ------------------------------------------------------------------ AT-08: cleared risk

  @Test
  @DisplayName("AT-08 a cleared risk cancels the case nobody has started on")
  void clearedRiskCancelsAnUntouchedCase() {
    String bag = bagTag();
    handler.handle(high(bag, "LEG-2", in(15)));

    Outcome cleared = handler.handle(event(eventId(), bag, "LEG-2", RiskLevel.LOW, in(15)));

    assertThat(cleared).isEqualTo(Outcome.CASE_CANCELLED);
    RecoveryCaseEntity c = casesOf(bag).getFirst();
    assertThat(c.getState()).isEqualTo(RecoveryState.CANCELLED);
    var last = transitions.findByCaseIdOrderByVersionAfterAsc(c.getId()).getLast();
    assertThat(last.getActor()).isEqualTo("system");
    assertThat(last.getReason()).startsWith("RISK_CLEARED");
  }

  @Test
  @DisplayName("AT-08 a cleared risk with no case creates no false case")
  void clearedRiskWithoutACaseCreatesNothing() {
    String bag = bagTag();

    Outcome cleared = handler.handle(event(eventId(), bag, "LEG-2", RiskLevel.LOW, in(15)));

    assertThat(cleared).isEqualTo(Outcome.NOTHING_TO_CANCEL);
    assertThat(casesOf(bag)).isEmpty();
  }

  @Test
  @DisplayName("AT-08 a cleared risk never cancels a case somebody is already working on")
  void clearedRiskKeepsACaseInProgress() {
    String bag = bagTag();
    handler.handle(high(bag, "LEG-2", in(15)));
    RecoveryCaseEntity c = casesOf(bag).getFirst();
    service.transition(
        c.getId(), new TransitionRequest(RecoveryState.ASSESSING, 1L, "Agent picked it up"));

    Outcome cleared = handler.handle(event(eventId(), bag, "LEG-2", RiskLevel.MEDIUM, in(15)));

    assertThat(cleared).isEqualTo(Outcome.NOTHING_TO_CANCEL);
    assertThat(casesOf(bag).getFirst().getState()).isEqualTo(RecoveryState.ASSESSING);
  }

  // ------------------------------------------------------------------ poison events

  @Test
  @DisplayName("Poison an event that is not JSON is refused for good and leaves no trace")
  void notJsonIsPoison() {
    assertThatThrownBy(() -> handler.handle("this is {not json"))
        .isInstanceOf(PoisonEventException.class)
        .hasMessage("event is not readable");
  }

  @Test
  @DisplayName("Poison an empty event is refused for good")
  void emptyEventIsPoison() {
    assertThatThrownBy(() -> handler.handle("  ")).isInstanceOf(PoisonEventException.class);
    assertThatThrownBy(() -> handler.handle(null)).isInstanceOf(PoisonEventException.class);
  }

  @Test
  @DisplayName("Poison an event of another type is refused")
  void otherEventTypeIsPoison() {
    String json = high(bagTag(), "LEG-2", in(15)).replace("baggage.risk.changed", "baggage.other");

    assertThatThrownBy(() -> handler.handle(json))
        .isInstanceOf(PoisonEventException.class)
        .hasMessage("unexpected event type");
  }

  @Test
  @DisplayName("Poison a bag tag or leg id that breaks the contract is refused")
  void badIdentifiersArePoison() {
    assertThatThrownBy(() -> handler.handle(high("AB12", "LEG-2", in(15))))
        .isInstanceOf(PoisonEventException.class)
        .hasMessage("bagTag is not a bag tag");
    assertThatThrownBy(() -> handler.handle(high(bagTag(), "bad leg id!", in(15))))
        .isInstanceOf(PoisonEventException.class)
        .hasMessage("legId is not a leg id");
  }

  @Test
  @DisplayName("Poison a risk level this version does not know is refused")
  void unknownRiskLevelIsPoison() {
    String json = high(bagTag(), "LEG-2", in(15)).replace("\"HIGH\"", "\"EXTREME\"");

    assertThatThrownBy(() -> handler.handle(json))
        .isInstanceOf(PoisonEventException.class)
        .hasMessage("risk level is missing or unknown");
  }

  @Test
  @DisplayName("Poison a refused event is not recorded as processed, so a fixed replay still works")
  void refusedEventIsNotMarkedProcessed() {
    String id = eventId();
    String bad = event(id, "AB12", "LEG-2", RiskLevel.HIGH, in(15));

    assertThatThrownBy(() -> handler.handle(bad)).isInstanceOf(PoisonEventException.class);

    assertThat(processed.existsById(id)).isFalse();
  }
}
