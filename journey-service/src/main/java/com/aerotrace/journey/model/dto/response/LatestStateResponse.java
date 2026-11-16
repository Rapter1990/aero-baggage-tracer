package com.aerotrace.journey.model.dto.response;

import java.time.Instant;
import java.util.UUID;

/** The authoritative state: the newest accepted scan by event time. */
public record LatestStateResponse(
    UUID scanId, String airport, String checkpoint, Instant occurredAt) {}
