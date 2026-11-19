package com.aerotrace.capacity.model.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * A pool of slots (a recovery cart, flight or bag-slot pool; the recovery-service uses the leg id).
 * Read-only in Java: the counts change only through the guarded UPDATEs of the repository, so the
 * database decides, never a value read earlier.
 */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "capacity_resource", schema = "capacity")
public class CapacityResourceEntity {

  @Id
  @Column(length = 32)
  private String id;

  @Column(name = "total_slots", nullable = false, insertable = false, updatable = false)
  private int totalSlots;

  @Column(name = "remaining_slots", nullable = false, insertable = false, updatable = false)
  private int remainingSlots;

  // Who created and last changed the resource, and when. Written by the guarded native SQL of the
  // repository (it does not fire JPA auditing), so they are read-only here.
  @Column(name = "created_by", nullable = false, insertable = false, updatable = false)
  private String createdBy;

  @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "updated_by", nullable = false, insertable = false, updatable = false)
  private String updatedBy;

  @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
  private Instant updatedAt;
}
