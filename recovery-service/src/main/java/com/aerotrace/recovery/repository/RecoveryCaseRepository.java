package com.aerotrace.recovery.repository;

import com.aerotrace.common.model.RecoveryState;
import com.aerotrace.recovery.model.entity.RecoveryCaseEntity;
import com.aerotrace.recovery.strategy.RecoveryAction;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RecoveryCaseRepository extends JpaRepository<RecoveryCaseEntity, UUID> {

  Optional<RecoveryCaseEntity> findByIdempotencyKey(String idempotencyKey);

  /** The active case of a bag and leg: the one whose state is not in the given final states. */
  Optional<RecoveryCaseEntity> findFirstByBagTagAndLegIdAndStateNotIn(
      String bagTag, String legId, Collection<RecoveryState> finalStates);

  /** Filters by state when given; the caller supplies a deterministic sort. */
  @Query("select c from RecoveryCaseEntity c where (:state is null or c.state = :state)")
  Page<RecoveryCaseEntity> search(@Param("state") RecoveryState state, Pageable pageable);

  /**
   * Moves a case to a new state and raises its version, but only while the version is still the one
   * the caller saw. Returns 0 when somebody else moved the case first (optimistic locking).
   */
  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query(
      "update RecoveryCaseEntity c set c.state = :to, c.version = c.version + 1,"
          + " c.reason = :reason, c.updatedAt = :now"
          + " where c.id = :id and c.version = :expectedVersion")
  int advance(
      @Param("id") UUID id,
      @Param("expectedVersion") long expectedVersion,
      @Param("to") RecoveryState to,
      @Param("reason") String reason,
      @Param("now") Instant now);

  /** Like {@link #advance} and records the capacity hold that was taken for the move. */
  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query(
      "update RecoveryCaseEntity c set c.state = :to, c.version = c.version + 1,"
          + " c.reason = :reason, c.updatedAt = :now, c.reservationId = :reservationId,"
          + " c.reservedAt = :now"
          + " where c.id = :id and c.version = :expectedVersion")
  int advanceAndHold(
      @Param("id") UUID id,
      @Param("expectedVersion") long expectedVersion,
      @Param("to") RecoveryState to,
      @Param("reason") String reason,
      @Param("now") Instant now,
      @Param("reservationId") UUID reservationId);

  /** Every case of a bag whose state is not in the given final states. */
  List<RecoveryCaseEntity> findByBagTagAndStateNotIn(
      String bagTag, Collection<RecoveryState> finalStates);

  /**
   * Records a scanner anomaly on a case without changing its state: the version rises (so a person
   * working on the case is told to reload) and the plan becomes the given action. Returns 0 when
   * the case was changed by someone else first.
   */
  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query(
      "update RecoveryCaseEntity c set c.version = c.version + 1, c.scannerAnomalyAt = :now,"
          + " c.plannedAction = :action, c.updatedAt = :now"
          + " where c.id = :id and c.version = :expectedVersion")
  int flagAnomaly(
      @Param("id") UUID id,
      @Param("expectedVersion") long expectedVersion,
      @Param("now") Instant now,
      @Param("action") RecoveryAction action);

  /**
   * Assigns a case to a handler without changing its state: the version rises, so a person working
   * on the case is told to reload. Returns 0 when the case was changed by someone else first.
   */
  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query(
      "update RecoveryCaseEntity c set c.assignedTo = :assignee, c.version = c.version + 1,"
          + " c.updatedAt = :now where c.id = :id and c.version = :expectedVersion")
  int assign(
      @Param("id") UUID id,
      @Param("expectedVersion") long expectedVersion,
      @Param("assignee") String assignee,
      @Param("now") Instant now);

  /** Like {@link #flagAnomaly} but leaves the plan of the case as it is. */
  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query(
      "update RecoveryCaseEntity c set c.version = c.version + 1, c.scannerAnomalyAt = :now,"
          + " c.updatedAt = :now where c.id = :id and c.version = :expectedVersion")
  int flagAnomalyKeepingPlan(
      @Param("id") UUID id,
      @Param("expectedVersion") long expectedVersion,
      @Param("now") Instant now);

  /** Forgets a hold after it was released; does nothing when the case holds another one. */
  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query(
      "update RecoveryCaseEntity c set c.reservationId = null"
          + " where c.id = :id and c.reservationId = :reservationId")
  int clearHold(@Param("id") UUID id, @Param("reservationId") UUID reservationId);

  /** Cases in a state whose hold was taken before the given time. */
  List<RecoveryCaseEntity> findByStateAndReservedAtBefore(RecoveryState state, Instant before);

  /** Final cases that still record a hold: their release failed and must be retried. */
  List<RecoveryCaseEntity> findByStateInAndReservationIdIsNotNull(Collection<RecoveryState> states);
}
