package com.aerotrace.common.event;

import com.aerotrace.common.model.RiskLevel;
import java.time.Instant;

/** Payload of {@link Topics#RISK_CHANGED}. */
public record RiskChangedPayload(
    String bagTag, String legId, RiskLevel previous, RiskLevel current, Instant departureAt) {}
