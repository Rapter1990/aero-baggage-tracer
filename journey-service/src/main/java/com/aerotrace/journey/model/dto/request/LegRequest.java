package com.aerotrace.journey.model.dto.request;

import com.aerotrace.journey.constant.ApiMessages;
import com.aerotrace.journey.constant.JourneyRules;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.time.Instant;

/** One flight. Times are ISO-8601 instants with an offset (for example 2026-11-14T08:00:00Z). */
public record LegRequest(
    @NotNull @Pattern(regexp = JourneyRules.FLIGHT_PATTERN, message = ApiMessages.FLIGHT_RULE)
        String flightNumber,
    @NotNull @Pattern(regexp = JourneyRules.AIRPORT_PATTERN, message = ApiMessages.AIRPORT_RULE)
        String origin,
    @NotNull @Pattern(regexp = JourneyRules.AIRPORT_PATTERN, message = ApiMessages.AIRPORT_RULE)
        String destination,
    @NotNull Instant departureAt,
    @NotNull Instant arrivalAt) {}
