package com.aerotrace.common.event;

import java.time.Instant;

/**
 * Payload of {@link Topics#SCAN_RECEIVED}. Times are UTC; the source timezone is retained.
 *
 * <p>Schema revision 2 added the leg the scan belongs to ({@code legId}, {@code flightNumber},
 * {@code departureAt}), so consumers need no join with the itinerary. All three are null when the
 * producer could not place the scan on a leg of the bag's itinerary, and in revision 1 events.
 */
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
    boolean lateEvent,
    String legId,
    String flightNumber,
    Instant departureAt) {}
