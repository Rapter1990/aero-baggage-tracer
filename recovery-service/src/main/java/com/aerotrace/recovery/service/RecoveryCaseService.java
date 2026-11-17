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
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
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

  private final RecoveryCaseRepository cases;
  private final StateTransitionRepository transitions;
  private final OutboxEventRepository outbox;
  private final Clock clock;
  private final MeterRegistry meters;
  private final TransactionTemplate transaction;

  public RecoveryCaseService(
      RecoveryCaseRepository cases,
      StateTransitionRepository transitions,
      OutboxEventRepository outbox,
      Clock clock,
      MeterRegistry meters,
      PlatformTransactionManager transactionManager) {
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
      RecoveryCaseEntity created = transaction.execute(status -> open(request, idempotencyKey));
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

  private RecoveryCaseEntity open(CreateCaseRequest request, String idempotencyKey) {
    Instant now = Instant.now(clock);
    String actor = actor();
    RecoveryCaseEntity created =
        cases.saveAndFlush(
            new RecoveryCaseEntity(
                request.bagTag(), request.legId(), request.reason(), idempotencyKey, actor, now));
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

  @Transactional
  public CaseResponse transition(UUID caseId, TransitionRequest request) {
    RecoveryCaseEntity current =
        cases
            .findById(caseId)
            .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, RecoveryRules.CASE_NOT_FOUND));
    if (current.getVersion() != request.expectedVersion()) {
      throw new ApiException(ErrorCode.VERSION_CONFLICT, RecoveryRules.VERSION_MISMATCH);
    }
    RecoveryState from = current.getState();
    RecoveryStateMachine.requireAllowed(from, request.targetState(), request.reason());

    Instant now = Instant.now(clock);
    int moved =
        cases.advance(
            caseId, request.expectedVersion(), request.targetState(), request.reason(), now);
    if (moved == 0) {
      // Somebody moved the case between the read and the update.
      throw new ApiException(ErrorCode.VERSION_CONFLICT, RecoveryRules.VERSION_MISMATCH);
    }
    RecoveryCaseEntity updated = cases.findById(caseId).orElseThrow();
    transitions.save(
        new StateTransitionEntity(
            caseId,
            from,
            request.targetState(),
            actor(),
            now,
            request.reason(),
            updated.getVersion()));
    queueEvent(updated, now);
    if (request.targetState() == RecoveryState.RESOLVED) {
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
