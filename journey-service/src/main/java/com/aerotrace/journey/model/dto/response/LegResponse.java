package com.aerotrace.journey.model.dto.response;

import com.aerotrace.journey.model.entity.ItineraryLegEntity;
import java.time.Instant;

public record LegResponse(
    int legNo,
    String flightNumber,
    String origin,
    String destination,
    Instant departureAt,
    Instant arrivalAt) {

  public static LegResponse from(ItineraryLegEntity leg) {
    return new LegResponse(
        leg.getLegNo(),
        leg.getFlightNumber(),
        leg.getOrigin(),
        leg.getDestination(),
        leg.getDepartureAt(),
        leg.getArrivalAt());
  }
}
