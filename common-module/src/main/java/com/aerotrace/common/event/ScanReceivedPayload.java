package com.aerotrace.common.event;

import java.time.Instant;

/** Payload of {@link Topics#SCAN_RECEIVED}. Times are UTC; the source timezone is retained. */
public record ScanReceivedPayload(
    String scanEventId,
    String bagTag,
    String airport,
    String checkpoint,
    long deviceSequence,
    Instant occurredAt,
    Instant deviceTimestamp,
    String sourceTimezone,
    Instant ingestedAt,
    boolean lateEvent) {}
