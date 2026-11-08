package com.aerotrace.common.event;

import com.aerotrace.common.model.AnomalyType;
import java.time.Instant;

/** Payload of {@link Topics#SCAN_ANOMALY}. */
public record ScanAnomalyPayload(
    String bagTag,
    AnomalyType anomalyType,
    int rejectedTransitions,
    Instant windowStart,
    Instant windowEnd) {}
