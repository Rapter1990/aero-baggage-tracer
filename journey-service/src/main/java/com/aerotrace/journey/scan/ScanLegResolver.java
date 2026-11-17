package com.aerotrace.journey.scan;

import com.aerotrace.common.model.Checkpoint;
import com.aerotrace.journey.model.entity.ItineraryLegEntity;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Places a scan on a leg of the bag's itinerary, so the scan event carries its leg and consumers
 * need no join.
 *
 * <p>CHECK_IN, SORTING, TRANSFER and LOADED happen before a departure, so they belong to a leg
 * whose origin is the scan airport; ARRIVED and CLAIM happen after a landing, so they belong to a
 * leg whose destination is the scan airport. Usually exactly one leg matches. When an airport is
 * visited twice the scan time decides: a departure-side scan belongs to the first matching leg that
 * has not yet departed (else the last one), an arrival-side scan to the last matching leg that has
 * already landed (else the first one). No match gives empty; the scan is still accepted.
 */
public final class ScanLegResolver {

  private ScanLegResolver() {}

  public static Optional<ItineraryLegEntity> resolve(
      List<ItineraryLegEntity> legs, String airport, Checkpoint checkpoint, Instant occurredAt) {
    boolean departureSide = isDepartureSide(checkpoint);
    List<ItineraryLegEntity> candidates =
        legs.stream()
            .filter(leg -> airport.equals(departureSide ? leg.getOrigin() : leg.getDestination()))
            .sorted(Comparator.comparingInt(ItineraryLegEntity::getLegNo))
            .toList();
    if (candidates.size() <= 1) {
      return candidates.stream().findFirst();
    }
    if (departureSide) {
      return candidates.stream()
          .filter(leg -> !leg.getDepartureAt().isBefore(occurredAt))
          .findFirst()
          .or(() -> Optional.of(candidates.getLast()));
    }
    return candidates.reversed().stream()
        .filter(leg -> !leg.getArrivalAt().isAfter(occurredAt))
        .findFirst()
        .or(() -> Optional.of(candidates.getFirst()));
  }

  private static boolean isDepartureSide(Checkpoint checkpoint) {
    return switch (checkpoint) {
      case CHECK_IN, SORTING, TRANSFER, LOADED -> true;
      case ARRIVED, CLAIM -> false;
    };
  }
}
