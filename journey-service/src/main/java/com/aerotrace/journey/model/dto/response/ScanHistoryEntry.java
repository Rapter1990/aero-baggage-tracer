package com.aerotrace.journey.model.dto.response;

import com.aerotrace.journey.model.entity.ScanAuditEntity;
import java.time.Instant;
import java.util.UUID;

/** One row of the immutable scan history. */
public record ScanHistoryEntry(
    UUID scanId,
    String protocol,
    String airport,
    String checkpoint,
    long deviceSequence,
    Instant occurredAt,
    Instant deviceTimestamp,
    String sourceTimezone,
    Instant ingestedAt,
    boolean lateEvent,
    boolean applied) {

  public static ScanHistoryEntry from(ScanAuditEntity scan) {
    return new ScanHistoryEntry(
        scan.getId(),
        scan.getProtocol(),
        scan.getAirport(),
        scan.getCheckpoint(),
        scan.getDeviceSequence(),
        scan.getOccurredAt(),
        scan.getDeviceTimestamp(),
        scan.getSourceTimezone(),
        scan.getIngestedAt(),
        scan.isLateEvent(),
        scan.isApplied());
  }
}
