package com.aerotrace.common.export;

import com.aerotrace.common.event.LegSnapshot;
import java.time.Instant;
import java.util.List;

/**
 * One bag of the journey-service's internal export ({@link ExportApi#BAGS}): its itinerary, as
 * {@code baggage.bag.registered.v1} carries it, and its latest accepted scan. No passenger data.
 *
 * @param bagTag the bag
 * @param legs every leg, in leg order
 * @param latestScan the latest accepted scan (by event time, then device sequence); null when the
 *     bag has not been scanned
 */
public record BagExport(String bagTag, List<LegSnapshot> legs, LatestScan latestScan) {

  /**
   * The latest accepted scan of a bag.
   *
   * @param airport where it was scanned
   * @param checkpoint the checkpoint, for example {@code TRANSFER}
   * @param legId the leg of the itinerary the scan belongs to, by the same rule as the scan event;
   *     null when it fits no leg
   * @param occurredAt the event time (UTC)
   * @param deviceSequence the scanner's sequence number; breaks ties of the same event time
   */
  public record LatestScan(
      String airport, String checkpoint, String legId, Instant occurredAt, long deviceSequence) {}
}
