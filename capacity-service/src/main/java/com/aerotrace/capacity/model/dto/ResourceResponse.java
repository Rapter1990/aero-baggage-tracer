package com.aerotrace.capacity.model.dto;

import com.aerotrace.capacity.model.entity.CapacityResourceEntity;

/** A resource with its total and the slots still free. */
public record ResourceResponse(String resourceId, int totalSlots, int remainingSlots) {

  public static ResourceResponse of(CapacityResourceEntity resource) {
    return new ResourceResponse(
        resource.getId(), resource.getTotalSlots(), resource.getRemainingSlots());
  }
}
