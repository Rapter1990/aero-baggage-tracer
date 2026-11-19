package com.aerotrace.search.indexer.model;

import java.time.Instant;

/**
 * One leg of a bag's itinerary, as {@code baggage.bag.registered.v1} (#28) and the bag export carry
 * it. {@code airline} is the validated carrier prefix of the flight number, decided by the
 * journey-service; the search-service never derives it.
 */
public record LegSnapshot(
    String legId,
    String flightNumber,
    String airline,
    String origin,
    String destination,
    Instant departureAt) {}
