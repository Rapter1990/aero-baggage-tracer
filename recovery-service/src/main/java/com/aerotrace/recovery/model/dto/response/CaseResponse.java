package com.aerotrace.recovery.model.dto.response;

import com.aerotrace.common.model.RecoveryState;
import com.aerotrace.recovery.model.entity.RecoveryCaseEntity;
import com.aerotrace.recovery.model.entity.StateTransitionEntity;
import com.aerotrace.recovery.strategy.RecoveryAction;
import java.time.Instant;
import java.util.UUID;

/** A recovery case as the API shows it. */
public record CaseResponse(
    UUID caseId,
    String bagTag,
    String legId,
    RecoveryState state,
    long version,
    String reason,
    RecoveryAction plannedAction,
    String assignedTo,
    Instant scannerAnomalyAt,
    Instant createdAt,
    Instant updatedAt) {

  public static CaseResponse of(RecoveryCaseEntity c) {
    return new CaseResponse(
        c.getId(),
        c.getBagTag(),
        c.getLegId(),
        c.getState(),
        c.getVersion(),
        c.getReason(),
        c.getPlannedAction(),
        c.getAssignedTo(),
        c.getScannerAnomalyAt(),
        c.getCreatedAt(),
        c.getUpdatedAt());
  }

  /**
   * The case as the move {@code t} left it: the answer to a retried move. State, version, reason
   * and time are those of the move, so a retry sees what the original request saw even when the
   * case has moved on since; the rest is the case now.
   */
  public static CaseResponse asOf(RecoveryCaseEntity c, StateTransitionEntity t) {
    return new CaseResponse(
        c.getId(),
        c.getBagTag(),
        c.getLegId(),
        t.getToState(),
        t.getVersionAfter(),
        t.getReason(),
        c.getPlannedAction(),
        c.getAssignedTo(),
        c.getScannerAnomalyAt(),
        c.getCreatedAt(),
        t.getOccurredAt());
  }
}
