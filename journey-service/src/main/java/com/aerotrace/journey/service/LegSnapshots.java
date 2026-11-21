package com.aerotrace.journey.service;

import com.aerotrace.common.event.LegSnapshot;
import com.aerotrace.journey.constant.JourneyRules;
import com.aerotrace.journey.model.entity.ItineraryLegEntity;

/**
 * A leg as {@code baggage.bag.registered.v1} and the internal bag export carry it: one mapping for
 * both, so the event and the rebuild source cannot drift apart.
 */
final class LegSnapshots {

  private LegSnapshots() {}

  static LegSnapshot of(ItineraryLegEntity leg) {
    return new LegSnapshot(
        leg.getId().toString(),
        leg.getFlightNumber(),
        JourneyRules.airlineOf(leg.getFlightNumber()),
        leg.getOrigin(),
        leg.getDestination(),
        leg.getDepartureAt());
  }
}
