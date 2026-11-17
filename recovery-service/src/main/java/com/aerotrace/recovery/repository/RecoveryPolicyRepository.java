package com.aerotrace.recovery.repository;

import com.aerotrace.recovery.model.entity.RecoveryPolicyEntity;
import java.time.Instant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RecoveryPolicyRepository extends JpaRepository<RecoveryPolicyEntity, String> {

  /**
   * Changes a policy and raises its version, but only while the version is still the one the admin
   * saw. Returns 0 when somebody changed it first (optimistic locking).
   */
  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query(
      "update RecoveryPolicyEntity p set p.minimumExpediteMinutes = :minutes,"
          + " p.version = p.version + 1, p.updatedBy = :by, p.updatedAt = :now"
          + " where p.key = :key and p.version = :expectedVersion")
  int change(
      @Param("key") String key,
      @Param("expectedVersion") long expectedVersion,
      @Param("minutes") int minutes,
      @Param("by") String updatedBy,
      @Param("now") Instant now);
}
