package com.aerotrace.recovery.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.aerotrace.common.correlation.CorrelationId;
import com.aerotrace.common.event.CaseChangedPayload;
import com.aerotrace.common.event.EventEnvelope;
import com.aerotrace.common.event.EventJson;
import com.aerotrace.common.event.RiskChangedPayload;
import com.aerotrace.common.event.ScanAnomalyPayload;
import com.aerotrace.common.model.AnomalyType;
import com.aerotrace.common.model.CaseTrigger;
import com.aerotrace.common.model.RecoveryState;
import com.aerotrace.common.model.RiskLevel;
import com.aerotrace.recovery.base.AbstractRecoveryTest;
import com.aerotrace.recovery.consumer.RiskEventHandler;
import com.aerotrace.recovery.consumer.ScanAnomalyHandler;
import com.aerotrace.recovery.model.dto.request.AssignRequest;
import com.aerotrace.recovery.model.dto.request.CreateCaseRequest;
import com.aerotrace.recovery.model.dto.request.TransitionRequest;
import com.aerotrace.recovery.model.dto.response.CaseResponse;
import com.aerotrace.recovery.model.entity.OutboxEventEntity;
import com.aerotrace.recovery.model.entity.RecoveryCaseEntity;
import com.aerotrace.recovery.repository.OutboxEventRepository;
import com.aerotrace.recovery.repository.RecoveryCaseRepository;
import com.aerotrace.recovery.service.RecoveryCaseService;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * The contract of the events the recovery-service writes to {@code recovery.case.changed.v1}:
 * revision 2 on every case event, and the correlation id of the event that opened or changed the
 * case.
 */
@SpringBootTest
class CaseEventContractTest extends AbstractRecoveryTest {

  @Autowired RecoveryCaseService service;
  @Autowired RiskEventHandler riskEvents;
  @Autowired ScanAnomalyHandler anomalies;
  @Autowired OutboxEventRepository outbox;
  @Autowired RecoveryCaseRepository cases;

  private static String newBagTag() {
    return String.format("%010d", ThreadLocalRandom.current().nextLong(10_000_000_000L));
  }

  /** A HIGH risk event for the bag and leg, 15 minutes before departure. */
  static String highRisk(String bagTag, String legId, String correlationId) {
    return EventJson.write(
        new EventEnvelope<>(
            "EV-" + UUID.randomUUID(),
            "baggage.risk.changed",
            1,
            bagTag + ":" + legId,
            Instant.now(),
            Instant.now(),
            correlationId,
            new RiskChangedPayload(
                bagTag,
                legId,
                RiskLevel.LOW,
                RiskLevel.HIGH,
                Instant.now().plus(15, ChronoUnit.MINUTES))));
  }

  private static String scannerAnomaly(String bagTag, String correlationId) {
    Instant start = Instant.now().minus(5, ChronoUnit.MINUTES);
    return EventJson.write(
        new EventEnvelope<>(
            "EV-" + UUID.randomUUID(),
            "baggage.scan.anomaly",
            1,
            bagTag,
            Instant.now(),
            Instant.now(),
            correlationId,
            new ScanAnomalyPayload(
                bagTag, AnomalyType.SCANNER_ANOMALY, 3, start, start.plus(5, ChronoUnit.MINUTES))));
  }

  private List<EventEnvelope<CaseChangedPayload>> eventsOf(String caseId) {
    return outbox.findByMessageKeyOrderByCreatedAt(caseId).stream()
        .map(OutboxEventEntity::getPayload)
        .map(json -> EventJson.read(json, CaseChangedPayload.class))
        .toList();
  }

  private RecoveryCaseEntity caseOf(String bagTag) {
    return cases.findAll().stream()
        .filter(c -> c.getBagTag().equals(bagTag))
        .findFirst()
        .orElseThrow();
  }

  @Test
  @DisplayName(
      "every case event is revision 2 with openedAt, resolvedAt, trigger and assignedTo, from"
          + " opening to resolution")
  void everyCaseEventIsRevisionTwo() {
    CaseResponse opened =
        service.create(new CreateCaseRequest(newBagTag(), "LEG-2", "opened"), null);
    UUID id = opened.caseId();
    long v = service.assign(id, new AssignRequest("agent-9", 1L)).version();
    v =
        service
            .transition(id, new TransitionRequest(RecoveryState.ASSESSING, v, "check"))
            .version();
    v = service.transition(id, new TransitionRequest(RecoveryState.RESERVED, v, "hold")).version();
    v = service.transition(id, new TransitionRequest(RecoveryState.IN_TRANSIT, v, "go")).version();
    CaseResponse resolved =
        service.transition(id, new TransitionRequest(RecoveryState.RESOLVED, v, "delivered"));

    List<EventEnvelope<CaseChangedPayload>> events = eventsOf(id.toString());

    assertThat(events).hasSize(6);
    assertThat(events).allSatisfy(e -> assertThat(e.schemaVersion()).isEqualTo(2));
    assertThat(events)
        .extracting(e -> e.payload().version())
        .containsExactly(1L, 2L, 3L, 4L, 5L, 6L);
    assertThat(events)
        .allSatisfy(
            e -> {
              assertThat(e.payload().trigger()).isEqualTo(CaseTrigger.MANUAL);
              assertThat(e.payload().openedAt())
                  .isCloseTo(opened.createdAt(), within(1, ChronoUnit.MILLIS));
            });
    assertThat(events.getFirst().payload().assignedTo()).isNull();
    assertThat(events.get(1).payload().assignedTo()).isEqualTo("agent-9");
    // resolvedAt appears with the move to RESOLVED, and only then.
    assertThat(events.subList(0, 5)).allSatisfy(e -> assertThat(e.payload().resolvedAt()).isNull());
    CaseChangedPayload last = events.getLast().payload();
    assertThat(last.state()).isEqualTo(RecoveryState.RESOLVED);
    assertThat(last.assignedTo()).isEqualTo("agent-9");
    assertThat(last.resolvedAt())
        .isCloseTo(resolved.updatedAt(), within(1, ChronoUnit.MILLIS))
        .isCloseTo(events.getLast().occurredAt(), within(1, ChronoUnit.MILLIS));
  }

  @Test
  @DisplayName("a cancelled case has no resolvedAt: only RESOLVED sets it")
  void cancelledCaseHasNoResolvedAt() {
    CaseResponse opened =
        service.create(new CreateCaseRequest(newBagTag(), "LEG-2", "opened"), null);
    service.transition(
        opened.caseId(), new TransitionRequest(RecoveryState.CANCELLED, 1L, "not needed"));

    CaseChangedPayload last = eventsOf(opened.caseId().toString()).getLast().payload();

    assertThat(last.state()).isEqualTo(RecoveryState.CANCELLED);
    assertThat(last.resolvedAt()).isNull();
  }

  @Test
  @DisplayName(
      "a case opened from a risk event has trigger RISK and carries the originating correlationId")
  void riskCaseCarriesTheCorrelationId() {
    String bag = newBagTag();
    String correlation = "corr-risk-" + UUID.randomUUID().toString().substring(0, 8);
    MDC.remove(CorrelationId.MDC_KEY);

    riskEvents.handle(highRisk(bag, "LEG-4", correlation));

    EventEnvelope<CaseChangedPayload> opened = eventsOf(caseOf(bag).getId().toString()).getFirst();
    assertThat(opened.correlationId()).isEqualTo(correlation);
    assertThat(opened.schemaVersion()).isEqualTo(2);
    assertThat(opened.payload().trigger()).isEqualTo(CaseTrigger.RISK);
    assertThat(opened.payload().openedAt()).isNotNull();
    // The id is not left behind for whatever the thread does next.
    assertThat(MDC.get(CorrelationId.MDC_KEY)).isNull();
  }

  @Test
  @DisplayName("a case flagged by a scanner anomaly event carries that event's correlationId")
  void anomalyFlagCarriesTheCorrelationId() {
    String bag = newBagTag();
    riskEvents.handle(highRisk(bag, "LEG-5", "corr-open"));
    String correlation = "corr-anomaly-" + UUID.randomUUID().toString().substring(0, 8);

    anomalies.handle(scannerAnomaly(bag, correlation));

    EventEnvelope<CaseChangedPayload> flagged = eventsOf(caseOf(bag).getId().toString()).getLast();
    assertThat(flagged.payload().version()).isEqualTo(2);
    assertThat(flagged.correlationId()).isEqualTo(correlation);
    assertThat(MDC.get(CorrelationId.MDC_KEY)).isNull();
  }

  @Test
  @DisplayName("an event without a usable correlationId gets none: it is never generated")
  void noCorrelationIdIsGenerated() {
    String bag = newBagTag();

    riskEvents.handle(highRisk(bag, "LEG-6", null));

    assertThat(eventsOf(caseOf(bag).getId().toString()).getFirst().correlationId()).isNull();
  }
}
