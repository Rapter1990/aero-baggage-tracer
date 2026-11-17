package com.aerotrace.recovery.model.entity;

import com.aerotrace.common.model.RecoveryState;
import com.aerotrace.recovery.strategy.RecoveryAction;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.UuidGenerator;

/**
 * A recovery case for one bag and one itinerary leg. The state and version change only through
 * {@code RecoveryCaseRepository#advance}, a guarded UPDATE, so two concurrent moves can never both
 * succeed (optimistic locking by the version column, which starts at 1).
 */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "recovery_case", schema = "recovery")
public class RecoveryCaseEntity {

  @Id @GeneratedValue @UuidGenerator private UUID id;

  @Column(name = "bag_tag", nullable = false, updatable = false, length = 10)
  private String bagTag;

  @Column(name = "leg_id", nullable = false, updatable = false, length = 32)
  private String legId;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 30)
  private RecoveryState state;

  @Column(nullable = false)
  private long version;

  @Column(nullable = false, length = 500)
  private String reason;

  /** The action the recovery strategy chose when the case was opened from a risk event. */
  @Enumerated(EnumType.STRING)
  @Column(name = "planned_action", length = 30)
  private RecoveryAction plannedAction;

  /** When a scanner anomaly was recorded on the bag of this case; null when none was. */
  @Column(name = "scanner_anomaly_at")
  private Instant scannerAnomalyAt;

  /** The capacity hold of this case in the capacity-service; null when there is none. */
  @Column(name = "reservation_id")
  private UUID reservationId;

  /** When the hold was made; the hold expires a configured time after this. */
  @Column(name = "reserved_at")
  private Instant reservedAt;

  @Column(name = "idempotency_key", updatable = false, length = 128)
  private String idempotencyKey;

  @Column(name = "created_by", nullable = false, updatable = false, length = 100)
  private String createdBy;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  public RecoveryCaseEntity(
      String bagTag,
      String legId,
      String reason,
      String idempotencyKey,
      String createdBy,
      Instant createdAt,
      RecoveryAction plannedAction) {
    this.plannedAction = plannedAction;
    this.bagTag = bagTag;
    this.legId = legId;
    this.reason = reason;
    this.idempotencyKey = idempotencyKey;
    this.createdBy = createdBy;
    this.createdAt = createdAt;
    this.updatedAt = createdAt;
    this.state = RecoveryState.OPEN;
    this.version = 1;
  }
}
