package com.aerotrace.capacity.model.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * A hold on slots of one resource. The id is chosen by the caller. Rows are inserted and closed by
 * guarded statements in the repository (never by saving this object), so a state is changed exactly
 * once whatever the number of parallel callers.
 */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "capacity_reservation", schema = "capacity")
public class CapacityReservationEntity {

  @Id private UUID id;

  @Column(name = "resource_id", nullable = false, insertable = false, updatable = false)
  private String resourceId;

  @Column(name = "case_id", nullable = false, insertable = false, updatable = false)
  private UUID caseId;

  @Column(name = "bag_tag", nullable = false, insertable = false, updatable = false)
  private String bagTag;

  @Column(nullable = false, insertable = false, updatable = false)
  private int slots;

  @Column(nullable = false, insertable = false, updatable = false)
  private String status;

  @Column(name = "idempotency_key", insertable = false, updatable = false)
  private String idempotencyKey;

  @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "confirmed_at", insertable = false, updatable = false)
  private Instant confirmedAt;

  @Column(name = "released_at", insertable = false, updatable = false)
  private Instant releasedAt;
}
