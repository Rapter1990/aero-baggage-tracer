package com.aerotrace.capacity.repository;

import com.aerotrace.capacity.model.entity.CapacityResourceEntity;
import java.time.Instant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * BR-04 lives here: every change of the counts is one conditional UPDATE. PostgreSQL locks the row,
 * waits for a concurrent change to commit, and re-checks the condition on the new value, so 50
 * parallel requests for 10 slots take exactly 10. Each statement flushes and clears the persistence
 * context, so a read that follows in the same transaction sees the new counts.
 */
public interface CapacityResourceRepository extends JpaRepository<CapacityResourceEntity, String> {

  /** Takes slots; 0 rows means the resource does not exist or has fewer slots left. */
  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query(
      value =
          "UPDATE capacity.capacity_resource SET remaining_slots = remaining_slots - :slots,"
              + " updated_at = :now WHERE id = :id AND remaining_slots >= :slots",
      nativeQuery = true)
  int take(@Param("id") String id, @Param("slots") int slots, @Param("now") Instant now);

  /** Returns slots of a closed hold. The CHECK constraint keeps the count within the total. */
  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query(
      value =
          "UPDATE capacity.capacity_resource SET remaining_slots = remaining_slots + :slots,"
              + " updated_at = :now WHERE id = :id",
      nativeQuery = true)
  int giveBack(@Param("id") String id, @Param("slots") int slots, @Param("now") Instant now);

  /** Creates a resource; 0 rows means it already exists. */
  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query(
      value =
          "INSERT INTO capacity.capacity_resource (id, total_slots, remaining_slots, updated_at)"
              + " VALUES (:id, :total, :total, :now) ON CONFLICT (id) DO NOTHING",
      nativeQuery = true)
  int createIfAbsent(@Param("id") String id, @Param("total") int total, @Param("now") Instant now);

  /**
   * Sets the total; the remaining count moves by the same difference. 0 rows means the resource
   * does not exist or the new total is lower than the slots already held.
   */
  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query(
      value =
          "UPDATE capacity.capacity_resource SET"
              + " remaining_slots = remaining_slots + (:total - total_slots),"
              + " total_slots = :total, updated_at = :now"
              + " WHERE id = :id AND remaining_slots + (:total - total_slots) >= 0",
      nativeQuery = true)
  int resize(@Param("id") String id, @Param("total") int total, @Param("now") Instant now);
}
