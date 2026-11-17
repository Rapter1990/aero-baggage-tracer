package com.aerotrace.recovery.repository;

import com.aerotrace.common.model.RecoveryState;
import com.aerotrace.recovery.model.entity.RecoveryCaseEntity;
import java.time.Instant;
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
}
