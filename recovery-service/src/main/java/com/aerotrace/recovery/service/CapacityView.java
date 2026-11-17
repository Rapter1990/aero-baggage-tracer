package com.aerotrace.recovery.service;

import com.aerotrace.recovery.repository.CapacitySnapshotRepository;
import org.springframework.stereotype.Component;

/**
 * Whether recovery capacity is believed to be available, from the snapshots of {@code
 * recovery.capacity.changed.v1}. A hint for choosing an action only: nothing is reserved on it, the
 * capacity-service decides every reservation.
 */
@Component
public class CapacityView {

  private final CapacitySnapshotRepository snapshots;

  public CapacityView(CapacitySnapshotRepository snapshots) {
    this.snapshots = snapshots;
  }

  /**
   * True when some resource has a free slot, or when nothing is known yet (before the first
   * capacity event the plan stays optimistic and the reservation itself answers 409 if it is
   * wrong).
   */
  public boolean available() {
    return snapshots.count() == 0 || snapshots.existsByRemainingSlotsGreaterThan(0);
  }
}
