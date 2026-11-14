package com.aerotrace.journey.model.dto.request;

import com.aerotrace.journey.constant.ApiMessages;
import com.aerotrace.journey.constant.JourneyRules;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

/** Registers a bag with the itinerary it is booked on. */
public record RegisterBagRequest(
    @NotNull @Pattern(regexp = JourneyRules.BAG_TAG_PATTERN, message = ApiMessages.BAG_TAG_RULE)
        String bagTag,
    @NotNull
        @Pattern(
            regexp = JourneyRules.PASSENGER_REF_PATTERN,
            message = ApiMessages.PASSENGER_REF_RULE)
        String passengerRef,
    @NotEmpty @Size(max = JourneyRules.MAX_LEGS, message = ApiMessages.LEGS_RULE)
        List<@Valid @NotNull LegRequest> legs) {}
