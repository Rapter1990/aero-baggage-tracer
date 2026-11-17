package com.aerotrace.recovery.service;

import com.aerotrace.common.correlation.CorrelationId;
import com.aerotrace.common.error.ApiException;
import com.aerotrace.common.error.ErrorCode;
import com.aerotrace.common.event.CaseChangedPayload;
import com.aerotrace.common.event.EventEnvelope;
import com.aerotrace.common.event.EventJson;
import com.aerotrace.common.event.Topics;
import com.aerotrace.common.model.RecoveryState;
import com.aerotrace.common.security.SecurityContextHelper;
import com.aerotrace.recovery.capacity.CapacityGateway;
import com.aerotrace.recovery.constant.MetricNames;
import com.aerotrace.recovery.constant.RecoveryRules;
import com.aerotrace.recovery.domain.RecoveryStateMachine;
import com.aerotrace.recovery.model.dto.request.CreateCaseRequest;
import com.aerotrace.recovery.model.dto.request.TransitionRequest;
import com.aerotrace.recovery.model.dto.response.CaseResponse;
import com.aerotrace.recovery.model.dto.response.PageResponse;
import com.aerotrace.recovery.model.entity.OutboxEventEntity;
import com.aerotrace.recovery.model.entity.RecoveryCaseEntity;
import com.aerotrace.recovery.model.entity.StateTransitionEntity;
import com.aerotrace.recovery.repository.OutboxEventRepository;
import com.aerotrace.recovery.repository.RecoveryCaseRepository;
import com.aerotrace.recovery.repository.StateTransitionRepository;
import com.aerotrace.recovery.strategy.RecoveryAction;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Opens recovery cases and moves them along the state machine. Every change is written together
 * with its state_transition row (who/when/why) and its outbox event in one transaction.
 *
 * <p>BR-01: one active case per bag and leg is decided by a unique partial index in the database,
 * not by a check in the code, so two parallel requests can never both win. BR-05 and BR-07 are the
 * state machine's guards. Concurrent moves are decided by the version column.
 */
@Service
public class RecoveryCaseService {

  /** The states a case never leaves: it is not "active" once it reached one of them. */
  private static final List<RecoveryState> FINAL_STATES =
      List.of(
          RecoveryState.RESOLVED, RecoveryState.CLOSED_AS_UNRECOVERABLE, RecoveryState.CANCELLED);

  private final RecoveryCaseRepository cases;
  private final StateTransitionRepository transitions;
  private final OutboxEventRepository outbox;
  private final Clock clock;
  private final MeterRegistry meters;
  private final CapacityGateway capacity;
  private final TransactionTemplate transaction;

  public RecoveryCaseService(
      RecoveryCaseRepository cases,
      StateTransitionRepository transitions,
      OutboxEventRepository outbox,
      Clock clock,
      MeterRegistry meters,
      CapacityGateway capacity,
      PlatformTransactionManager transactionManager) {
    this.capacity = capacity;
    this.cases = cases;
    this.transitions = transitions;
    this.outbox = outbox;
    this.clock = clock;
    this.meters = meters;
    this.transaction = new TransactionTemplate(transactionManager);
  }

  /**
   * Opens a case. Not transactional itself: the insert runs in its own transaction so that a
   * unique-index violation can be caught and answered after it has been rolled back.
   */
  public CaseResponse create(CreateCaseRequest request, String idempotencyKey) {
    if (idempotencyKey != null) {
      var replay = cases.findByIdempotencyKey(idempotencyKey);
      if (replay.isPresent()) {
        return replay(replay.get(), request);
      }
    }
    try {
      RecoveryCaseEntity created =
          transaction.execute(status -> open(request, idempotencyKey, null));
      return CaseResponse.of(created);
    } catch (DataIntegrityViolationException conflict) {
      if (idempotencyKey != null) {
        // The same key arrived twice at once: the other request won, this one gets its answer.
        var winner = cases.findByIdempotencyKey(idempotencyKey);
        if (winner.isPresent()) {
          return replay(winner.get(), request);
        }
      }
      throw new ApiException(ErrorCode.DUPLICATE_RECOVERY_CASE, RecoveryRules.ACTIVE_CASE_EXISTS);
    }
  }

  /**
   * Opens a case for a bag at risk, from a risk event. Must run inside the caller's transaction
   * (the consumer's, together with its dedupe row). Returns false when the bag and leg already have
   * an active case: the event changes nothing then (BR-01).
   */
  public boolean openFromRisk(String bagTag, String legId, RecoveryAction plannedAction) {
    if (activeCase(bagTag, legId).isPresent()) {
      return false;
    }
    open(
        new CreateCaseRequest(bagTag, legId, RecoveryRules.HIGH_CONNECTION_RISK),
        null,
        plannedAction);
    return true;
  }

  /**
   * Cancels the active case of a bag and leg when the risk has cleared, but only while nobody has
   * started on it (state OPEN): a case being assessed or reserved is a person's work and stays.
   * Must run inside the caller's transaction.
   */
  public boolean cancelOpenCase(String bagTag, String legId, String reason) {
    return activeCase(bagTag, legId)
        .filter(c -> c.getState() == RecoveryState.OPEN)
        .map(
            c -> {
              apply(
                  c.getId(),
                  RecoveryState.CANCELLED,
                  c.getVersion(),
                  reason,
                  RecoveryRules.SYSTEM_ACTOR,
                  null);
              return true;
            })
        .orElse(false);
  }

  /**
   * Records a scanner anomaly (BR-03) on every active case of the bag that does not have one yet:
   * the scan data is unreliable, so each case is planned for the service desk, where an agent
   * checks the bag. The state does not change; the version rises and a history row and an event
   * record the flag. Returns how many cases were flagged. Must run inside the caller's transaction.
   */
  public int flagScannerAnomaly(String bagTag) {
    int flagged = 0;
    for (RecoveryCaseEntity c : cases.findByBagTagAndStateNotIn(bagTag, FINAL_STATES)) {
      if (c.getScannerAnomalyAt() != null) {
        continue;
      }
      Instant now = Instant.now(clock);
      int updated =
          cases.flagAnomaly(c.getId(), c.getVersion(), now, RecoveryAction.HOLD_AT_SERVICE_DESK);
      if (updated == 0) {
        throw new ApiException(ErrorCode.VERSION_CONFLICT, RecoveryRules.VERSION_MISMATCH);
      }
      RecoveryCaseEntity fresh = cases.findById(c.getId()).orElseThrow();
      transitions.save(
          new StateTransitionEntity(
              c.getId(),
              c.getState(),
              c.getState(),
              RecoveryRules.SYSTEM_ACTOR,
              now,
              RecoveryRules.SCANNER_ANOMALY,
              fresh.getVersion()));
      queueEvent(fresh, now);
      flagged++;
    }
    return flagged;
  }

  private Optional<RecoveryCaseEntity> activeCase(String bagTag, String legId) {
    return cases.findFirstByBagTagAndLegIdAndStateNotIn(bagTag, legId, FINAL_STATES);
  }

  private RecoveryCaseEntity open(
      CreateCaseRequest request, String idempotencyKey, RecoveryAction plannedAction) {
    Instant now = Instant.now(clock);
    String actor = actor();
    RecoveryCaseEntity created =
        cases.saveAndFlush(
            new RecoveryCaseEntity(
                request.bagTag(),
                request.legId(),
                request.reason(),
                idempotencyKey,
                actor,
                now,
                plannedAction));
    transitions.save(
        new StateTransitionEntity(
            created.getId(), null, RecoveryState.OPEN, actor, now, request.reason(), 1));
    queueEvent(created, now);
    return created;
  }

  /** A retry gets the original case, but only when it is the same request. */
  private static CaseResponse replay(RecoveryCaseEntity existing, CreateCaseRequest request) {
    boolean same =
        existing.getBagTag().equals(request.bagTag())
            && existing.getLegId().equals(request.legId());
    if (!same) {
      throw new ApiException(ErrorCode.VERSION_CONFLICT, RecoveryRules.IDEMPOTENCY_KEY_REUSED);
    }
    return CaseResponse.of(existing);
  }

  public CaseResponse transition(UUID caseId, TransitionRequest request) {
    return move(
        caseId, request.targetState(), request.expectedVersion(), request.reason(), actor());
  }

  /**
   * Moves a case. Moving to RESERVED is a saga over two systems that cannot share a transaction:
   *
   * <ol>
   *   <li>check the move is legal and the version current (nothing is held for a move that would be
   *       refused);
   *   <li>hold a slot in the capacity-service (its failure leaves the case untouched: 409 when
   *       full, 503 when it does not answer, after releasing a hold that may have been made);
   *   <li>in one local transaction: move the case, record the reservation id, write the history row
   *       and the outbox event;
   *   <li>if step 3 fails (a concurrent move won, the database failed), release the hold
   *       (compensation) and report the failure.
   * </ol>
   *
   * <p>A crash between steps 2 and 3 leaves a hold the case does not know. That window is closed by
   * the capacity-service expiring provisional holds. Cancelling a case releases its hold after the
   * commit; a release that fails keeps the reservation id on the case for the sweeper.
   */
  private CaseResponse move(
      UUID caseId, RecoveryState target, long expectedVersion, String reason, String actor) {
    RecoveryCaseEntity current =
        cases
            .findById(caseId)
            .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, RecoveryRules.CASE_NOT_FOUND));
    if (current.getVersion() != expectedVersion) {
      throw new ApiException(ErrorCode.VERSION_CONFLICT, RecoveryRules.VERSION_MISMATCH);
    }
    RecoveryStateMachine.requireAllowed(current.getState(), target, reason);

    UUID reservationId =
        target == RecoveryState.RESERVED
            ? capacity.reserve(caseId, current.getBagTag(), current.getLegId())
            : null;
    CaseResponse moved;
    try {
      moved =
          transaction.execute(
              status -> apply(caseId, target, expectedVersion, reason, actor, reservationId));
    } catch (RuntimeException failure) {
      if (reservationId != null) {
        // Compensation: the case did not move, so the hold must not stay.
        capacity.release(reservationId);
      }
      throw failure;
    }
    if (target == RecoveryState.CANCELLED || target == RecoveryState.CLOSED_AS_UNRECOVERABLE) {
      releaseHold(caseId);
    }
    return moved;
  }

  /** The local transaction of a move: state, history and event together. */
  private CaseResponse apply(
      UUID caseId,
      RecoveryState target,
      long expectedVersion,
      String reason,
      String actor,
      UUID reservationId) {
    RecoveryCaseEntity current =
        cases
            .findById(caseId)
            .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, RecoveryRules.CASE_NOT_FOUND));
    RecoveryState from = current.getState();
    RecoveryStateMachine.requireAllowed(from, target, reason);

    Instant now = Instant.now(clock);
    int moved =
        reservationId == null
            ? cases.advance(caseId, expectedVersion, target, reason, now)
            : cases.advanceAndHold(caseId, expectedVersion, target, reason, now, reservationId);
    if (moved == 0) {
      // Somebody moved the case between the read and the update.
      throw new ApiException(ErrorCode.VERSION_CONFLICT, RecoveryRules.VERSION_MISMATCH);
    }
    RecoveryCaseEntity updated = cases.findById(caseId).orElseThrow();
    transitions.save(
        new StateTransitionEntity(caseId, from, target, actor, now, reason, updated.getVersion()));
    queueEvent(updated, now);
    if (target == RecoveryState.RESOLVED) {
      Timer.builder(MetricNames.RECOVERY_RESOLUTION)
          .description("Time from opening a case to resolving it")
          .publishPercentileHistogram()
          .register(meters)
          .record(Duration.between(updated.getCreatedAt(), now));
    }
    return CaseResponse.of(updated);
  }

  @Transactional(readOnly = true)
  public PageResponse<CaseResponse> list(RecoveryState state, int page, int size) {
    int boundedSize = Math.min(Math.max(size, 1), RecoveryRules.MAX_PAGE_SIZE);
    // Oldest first, ties broken by id, so a page never shifts between two requests.
    var pageable =
        PageRequest.of(
            Math.max(page, 0), boundedSize, Sort.by("createdAt").ascending().and(Sort.by("id")));
    return PageResponse.of(cases.search(state, pageable), CaseResponse::of);
  }

  /**
   * Releases the hold of a case in the capacity-service and forgets it on success. A failed release
   * keeps the reservation id on the case, so {@link #retryReleases()} tries again.
   */
  public void releaseHold(UUID caseId) {
    cases
        .findById(caseId)
        .map(RecoveryCaseEntity::getReservationId)
        .ifPresent(
            reservationId -> {
              if (capacity.release(reservationId)) {
                transaction.executeWithoutResult(status -> cases.clearHold(caseId, reservationId));
              }
            });
  }

  /**
   * Compensation for a hold that ran out (AT-12): every case still RESERVED after {@code
   * expiredBefore} is cancelled with a clear reason and its hold released. A case that someone
   * moved in the meantime is left alone.
   */
  public int expireHolds(Instant expiredBefore) {
    int expired = 0;
    for (RecoveryCaseEntity c :
        cases.findByStateAndReservedAtBefore(RecoveryState.RESERVED, expiredBefore)) {
      try {
        move(
            c.getId(),
            RecoveryState.CANCELLED,
            c.getVersion(),
            RecoveryRules.RESERVATION_TIMEOUT,
            RecoveryRules.SYSTEM_ACTOR);
        expired++;
      } catch (ApiException movedByOthers) {
        // Moved or changed since it was listed: nothing to expire any more.
      }
    }
    return expired;
  }

  /** Tries again to release holds of cases that are already final. */
  public int retryReleases() {
    int retried = 0;
    for (RecoveryCaseEntity c :
        cases.findByStateInAndReservationIdIsNotNull(
            List.of(RecoveryState.CANCELLED, RecoveryState.CLOSED_AS_UNRECOVERABLE))) {
      releaseHold(c.getId());
      retried++;
    }
    return retried;
  }

  private void queueEvent(RecoveryCaseEntity c, Instant now) {
    CaseChangedPayload payload =
        new CaseChangedPayload(
            c.getId().toString(),
            c.getBagTag(),
            c.getLegId(),
            c.getState(),
            c.getVersion(),
            c.getReason());
    EventEnvelope<CaseChangedPayload> envelope =
        EventEnvelope.create(
            RecoveryRules.EVENT_TYPE_CASE_CHANGED,
            RecoveryRules.CASE_EVENT_SCHEMA_VERSION,
            c.getId().toString(),
            MDC.get(CorrelationId.MDC_KEY),
            now,
            payload,
            clock);
    outbox.save(
        new OutboxEventEntity(
            Topics.CASE_CHANGED, c.getId().toString(), EventJson.write(envelope), now));
  }

  private static String actor() {
    return SecurityContextHelper.currentSubject().orElse(RecoveryRules.SYSTEM_ACTOR);
  }
}
