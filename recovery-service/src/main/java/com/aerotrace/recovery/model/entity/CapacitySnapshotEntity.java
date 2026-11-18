package com.aerotrace.recovery.model.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

/**
 * The capacity of one resource as this service last heard of it. Written only by {@code
 * CapacitySnapshotRepository#upsert}; a read model for choosing a recovery action, never the
 * authority on capacity.
 */
@Entity
@Immutable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "capacity_snapshot", schema = "recovery")
public class CapacitySnapshotEntity {

  @Id
  @Column(name = "resource_id", length = 64)
  private String resourceId;

  @Column(name = "total_slots", nullable = false)
  private int totalSlots;

  @Column(name = "remaining_slots", nullable = false)
  private int remainingSlots;

  @Column(name = "occurred_at", nullable = false)
  private Instant occurredAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;
}
