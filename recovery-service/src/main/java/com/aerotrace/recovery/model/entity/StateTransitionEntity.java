package com.aerotrace.recovery.model.entity;

import com.aerotrace.common.model.RecoveryState;
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
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.UuidGenerator;

/**
 * One recorded move of a case: who moved it, when and why (BR-05). Rows are only ever inserted. The
 * first row of a case has no {@code fromState}: it is the opening.
 */
@Entity
@Immutable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "state_transition", schema = "recovery")
public class StateTransitionEntity {

  @Id @GeneratedValue @UuidGenerator private UUID id;

  @Column(name = "case_id", nullable = false)
  private UUID caseId;

  @Enumerated(EnumType.STRING)
  @Column(name = "from_state", length = 30)
  private RecoveryState fromState;

  @Enumerated(EnumType.STRING)
  @Column(name = "to_state", nullable = false, length = 30)
  private RecoveryState toState;

  @Column(nullable = false, length = 100)
  private String actor;

  @Column(name = "occurred_at", nullable = false)
  private Instant occurredAt;

  @Column(nullable = false, length = 500)
  private String reason;

  @Column(name = "version_after", nullable = false)
  private long versionAfter;

  /** The Idempotency-Key of the move, unique per case; null for moves made without one. */
  @Column(name = "idempotency_key", length = 128)
  private String idempotencyKey;

  public StateTransitionEntity(
      UUID caseId,
      RecoveryState fromState,
      RecoveryState toState,
      String actor,
      Instant occurredAt,
      String reason,
      long versionAfter) {
    this(caseId, fromState, toState, actor, occurredAt, reason, versionAfter, null);
  }

  public StateTransitionEntity(
      UUID caseId,
      RecoveryState fromState,
      RecoveryState toState,
      String actor,
      Instant occurredAt,
      String reason,
      long versionAfter,
      String idempotencyKey) {
    this.idempotencyKey = idempotencyKey;
    this.caseId = caseId;
    this.fromState = fromState;
    this.toState = toState;
    this.actor = actor;
    this.occurredAt = occurredAt;
    this.reason = reason;
    this.versionAfter = versionAfter;
  }
}
