package com.aerotrace.recovery.consumer;

import com.aerotrace.common.event.EventEnvelope;
import com.aerotrace.common.event.RiskChangedPayload;
import com.aerotrace.common.model.RiskLevel;
import com.aerotrace.recovery.constant.RecoveryRules;
import com.aerotrace.recovery.policy.PolicyService;
import com.aerotrace.recovery.repository.ProcessedEventRepository;
import com.aerotrace.recovery.repository.RiskEventClockRepository;
import com.aerotrace.recovery.service.CapacityView;
import com.aerotrace.recovery.service.RecoveryCaseService;
import com.aerotrace.recovery.strategy.ActionContext;
import com.aerotrace.recovery.strategy.RecoveryAction;
import com.aerotrace.recovery.strategy.RecoveryActionSelector;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Turns a {@code baggage.risk.changed.v1} event into recovery work, exactly once in effect:
 *
 * <ul>
 *   <li>HIGH: opens a case for the bag and leg (or does nothing when one is active, BR-01) and
 *       records the action the {@link RecoveryActionSelector} chose;
 *   <li>LOW or MEDIUM: cancels the case only while it is still OPEN, so a cleared risk leaves no
 *       false case behind (AT-08) and never overrides someone's work.
 * </ul>
 *
 * <p>The event id is written to {@code processed_event} in the same transaction as the effect, so a
 * redelivery (a Kafka retry, a restart, a second replica) changes nothing, and a failure rolls both
 * back together. Kafka's exactly-once would not cover this: the case is in PostgreSQL.
 */
@Component
public class RiskEventHandler {

  /** What an event led to; the listener ignores it, tests and metrics read it. */
  public enum Outcome {
    CASE_OPENED,
    CASE_ALREADY_ACTIVE,
    CASE_CANCELLED,
    NOTHING_TO_CANCEL,
    /** Older than a risk event already applied to the bag and leg: changes nothing. */
    STALE_EVENT,
    DUPLICATE
  }

  private static final Pattern BAG_TAG = Pattern.compile(RecoveryRules.BAG_TAG_PATTERN);
  private static final Pattern LEG_ID = Pattern.compile(RecoveryRules.LEG_ID_PATTERN);

  private final RecoveryCaseService cases;
  private final ProcessedEventRepository processed;
  private final PolicyService policy;
  private final CapacityView capacity;
  private final RiskEventClockRepository riskClock;

  /** How far ahead of this service's clock an event may be dated (clocks differ a little). */
  private static final Duration MAX_CLOCK_SKEW = Duration.ofMinutes(5);

  private final Clock clock;
  private final MeterRegistry meters;
  private final TransactionTemplate transaction;

  public RiskEventHandler(
      RecoveryCaseService cases,
      ProcessedEventRepository processed,
      PolicyService policy,
      CapacityView capacity,
      RiskEventClockRepository riskClock,
      Clock clock,
      MeterRegistry meters,
      PlatformTransactionManager transactionManager) {
    this.riskClock = riskClock;
    this.capacity = capacity;
    this.cases = cases;
    this.processed = processed;
    this.policy = policy;
    this.clock = clock;
    this.meters = meters;
    this.transaction = new TransactionTemplate(transactionManager);
  }

  /**
   * Handles one event. A {@link PoisonEventException} means the event can never be handled; any
   * other exception is transient and the caller retries.
   */
  public Outcome handle(String json) {
    EventEnvelope<RiskChangedPayload> event = read(json);
    // An event time far in the future would win every later comparison in the risk clock and freeze
    // that bag and leg (a real LOW or HIGH after it would be ignored as stale), so a clock that
    // wrong makes the event unusable: it is dead-lettered, which also raises the alarm.
    if (event.occurredAt().isAfter(Instant.now(clock).plus(MAX_CLOCK_SKEW))) {
      throw new PoisonEventException("occurredAt is in the future");
    }
    Outcome outcome = transaction.execute(status -> process(event));
    meters.counter("recovery.risk.events", "outcome", outcome.name()).increment();
    return outcome;
  }

  private Outcome process(EventEnvelope<RiskChangedPayload> event) {
    Instant now = Instant.now(clock);
    if (!processed.markProcessed(EventReader.dedupeKey(event), now)) {
      return Outcome.DUPLICATE;
    }
    RiskChangedPayload risk = event.payload();
    // Events are applied by event time: one that is older than the newest already applied (a late
    // or replayed HIGH after the LOW that cleared it) must not undo that.
    if (riskClock.advance(risk.bagTag(), risk.legId(), event.occurredAt()) == 0) {
      return Outcome.STALE_EVENT;
    }
    if (risk.current() == RiskLevel.HIGH) {
      return cases.openFromRisk(risk.bagTag(), risk.legId(), plan(risk, now))
          ? Outcome.CASE_OPENED
          : Outcome.CASE_ALREADY_ACTIVE;
    }
    return cases.cancelOpenCase(risk.bagTag(), risk.legId(), RecoveryRules.RISK_CLEARED)
        ? Outcome.CASE_CANCELLED
        : Outcome.NOTHING_TO_CANCEL;
  }

  /**
   * The action for a bag at risk. Capacity comes from the snapshots of the capacity events (a hint:
   * the capacity-service is asked only when the case is reserved, and a refusal there is answered
   * with 409). Without a departure time nothing can be judged, so the bag is held at the service
   * desk.
   */
  private RecoveryAction plan(RiskChangedPayload risk, Instant now) {
    if (risk.departureAt() == null) {
      return RecoveryAction.HOLD_AT_SERVICE_DESK;
    }
    // The minimum expedite time is the admin-managed policy (cached in Redis, read from the
    // database when Redis is unavailable).
    RecoveryActionSelector selector =
        RecoveryActionSelector.withDefaults(policy.current().minimumExpediteTime());
    return selector.choose(
        new ActionContext(Duration.between(now, risk.departureAt()), capacity.available()));
  }

  private static EventEnvelope<RiskChangedPayload> read(String json) {
    EventEnvelope<RiskChangedPayload> event =
        EventReader.read(json, RecoveryRules.EVENT_TYPE_RISK_CHANGED, RiskChangedPayload.class);
    validate(event);
    return event;
  }

  private static void validate(EventEnvelope<RiskChangedPayload> event) {
    RiskChangedPayload risk = event.payload();
    if (risk.bagTag() == null || !BAG_TAG.matcher(risk.bagTag()).matches()) {
      throw new PoisonEventException("bagTag is not a bag tag");
    }
    if (risk.legId() == null || !LEG_ID.matcher(risk.legId()).matches()) {
      throw new PoisonEventException("legId is not a leg id");
    }
    if (risk.current() == null) {
      // Missing, or a risk level this version does not know (see EventJson's open enums).
      throw new PoisonEventException("risk level is missing or unknown");
    }
  }
}
