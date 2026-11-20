package com.aerotrace.common.event;

import java.time.Instant;

/**
 * One leg of a bag's itinerary as the journey-service registered it: in {@link
 * BagRegisteredPayload} and in the internal bag export. Times are UTC.
 *
 * @param legId the journey-service's id of the leg (the same id its scan events carry)
 * @param flightNumber the validated flight number, for example {@code TK1981}
 * @param airline the carrier prefix of the flight number, decided by the journey-service (its rule:
 *     the two-character IATA airline designator the validated flight number starts with); consumers
 *     never derive it themselves
 * @param origin the IATA code of the departure airport
 * @param destination the IATA code of the arrival airport
 * @param departureAt the scheduled departure
 */
public record LegSnapshot(
    String legId,
    String flightNumber,
    String airline,
    String origin,
    String destination,
    Instant departureAt) {}
