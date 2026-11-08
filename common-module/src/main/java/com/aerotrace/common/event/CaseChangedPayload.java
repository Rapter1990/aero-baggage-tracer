package com.aerotrace.common.event;

import com.aerotrace.common.model.RecoveryState;

/** Payload of {@link Topics#CASE_CHANGED}. */
public record CaseChangedPayload(
    String caseId, String bagTag, String legId, RecoveryState state, long version, String reason) {}
