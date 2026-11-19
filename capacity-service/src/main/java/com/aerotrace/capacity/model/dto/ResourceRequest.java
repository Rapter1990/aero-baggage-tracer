package com.aerotrace.capacity.model.dto;

import com.aerotrace.capacity.constant.CapacityRules;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** The total number of slots of a resource. */
public record ResourceRequest(
    @NotNull @Min(0) @Max(CapacityRules.MAX_TOTAL_SLOTS) Integer totalSlots) {}
