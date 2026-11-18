package com.aerotrace.recovery.repository;

import com.aerotrace.recovery.model.entity.CapacitySnapshotEntity;
import java.time.Instant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CapacitySnapshotRepository extends JpaRepository<CapacitySnapshotEntity, String> {

  /**
   * Stores the capacity of a resource unless a newer event was stored before: one atomic statement,
   * so replicas and redeliveries cannot interleave. Returns 1 when the row was written and 0 when
   * the event was older than the stored one.
   */
  @Modifying
  @Query(
      value =
          "INSERT INTO recovery.capacity_snapshot"
              + " (resource_id, total_slots, remaining_slots, occurred_at, updated_at)"
              + " VALUES (:id, :total, :remaining, :occurredAt, :now)"
              + " ON CONFLICT (resource_id) DO UPDATE SET total_slots = EXCLUDED.total_slots,"
              + " remaining_slots = EXCLUDED.remaining_slots, occurred_at = EXCLUDED.occurred_at,"
              + " updated_at = EXCLUDED.updated_at"
              + " WHERE recovery.capacity_snapshot.occurred_at <= EXCLUDED.occurred_at",
      nativeQuery = true)
  int upsert(
      @Param("id") String resourceId,
      @Param("total") int totalSlots,
      @Param("remaining") int remainingSlots,
      @Param("occurredAt") Instant occurredAt,
      @Param("now") Instant now);

  boolean existsByRemainingSlotsGreaterThan(int remaining);
}
