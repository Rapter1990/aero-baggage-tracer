package com.aerotrace.common.event;

/** Payload of {@link Topics#CAPACITY_CHANGED}. */
public record CapacityChangedPayload(String resourceId, int totalSlots, int remainingSlots) {}
