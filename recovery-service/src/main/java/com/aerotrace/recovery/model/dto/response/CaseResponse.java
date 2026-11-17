package com.aerotrace.recovery.model.dto.response;

import com.aerotrace.common.model.RecoveryState;
import com.aerotrace.recovery.model.entity.RecoveryCaseEntity;
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
        c.getCreatedAt(),
        c.getUpdatedAt());
  }
}
