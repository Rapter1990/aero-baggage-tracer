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

  // The next four are null only on a tombstone: a RELEASED row written when an unknown id was
  // released, so that a late hold with that id is refused.
  @Column(name = "resource_id", insertable = false, updatable = false)
  private String resourceId;

  @Column(name = "case_id", insertable = false, updatable = false)
  private UUID caseId;

  @Column(name = "bag_tag", insertable = false, updatable = false)
  private String bagTag;

  @Column(insertable = false, updatable = false)
  private Integer slots;

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

  // Who made the hold and who closed it (released it, or "system" when it expired). Written by the
  // guarded native SQL of the repository, which does not fire JPA auditing.
  @Column(name = "created_by", nullable = false, insertable = false, updatable = false)
  private String createdBy;

  @Column(name = "released_by", insertable = false, updatable = false)
  private String releasedBy;
}
