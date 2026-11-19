package com.aerotrace.capacity.model.dto;

import com.aerotrace.capacity.model.entity.CapacityResourceEntity;
import java.time.Instant;

/**
 * A resource with its total and the slots still free, and who created and last changed it, and when
 * (the user id of the caller, see ADR 0003).
 */
public record ResourceResponse(
    String resourceId,
    int totalSlots,
    int remainingSlots,
    String createdBy,
    Instant createdAt,
    String updatedBy,
    Instant updatedAt) {

  public static ResourceResponse of(CapacityResourceEntity resource) {
    return new ResourceResponse(
        resource.getId(),
        resource.getTotalSlots(),
        resource.getRemainingSlots(),
        resource.getCreatedBy(),
        resource.getCreatedAt(),
        resource.getUpdatedBy(),
        resource.getUpdatedAt());
  }
}
