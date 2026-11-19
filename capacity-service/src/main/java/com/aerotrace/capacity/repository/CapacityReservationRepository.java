package com.aerotrace.capacity.repository;

import com.aerotrace.capacity.model.entity.CapacityReservationEntity;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Holds are inserted and closed by guarded statements, so each state change happens once. */
public interface CapacityReservationRepository
    extends JpaRepository<CapacityReservationEntity, UUID> {

  Optional<CapacityReservationEntity> findByIdempotencyKey(String idempotencyKey);

  /** A plain INSERT: a second insert of the same id or key fails on the unique constraint. */
  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query(
      value =
          "INSERT INTO capacity.capacity_reservation"
              + " (id, resource_id, case_id, bag_tag, slots, status, idempotency_key, created_at)"
              + " VALUES (:id, :resourceId, :caseId, :bagTag, :slots, 'HELD', :key, :now)",
      nativeQuery = true)
  int insertHeld(
      @Param("id") UUID id,
      @Param("resourceId") String resourceId,
      @Param("caseId") UUID caseId,
      @Param("bagTag") String bagTag,
      @Param("slots") int slots,
      @Param("key") String idempotencyKey,
      @Param("now") Instant now);

  /** HELD to RELEASED; 0 rows means it was already closed (or never existed). */
  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query(
      value =
          "UPDATE capacity.capacity_reservation SET status = 'RELEASED', released_at = :now"
              + " WHERE id = :id AND status = 'HELD'",
      nativeQuery = true)
  int release(@Param("id") UUID id, @Param("now") Instant now);

  /** HELD to EXPIRED, only while nobody confirmed it and it is older than the cutoff. */
  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query(
      value =
          "UPDATE capacity.capacity_reservation SET status = 'EXPIRED', released_at = :now"
              + " WHERE id = :id AND status = 'HELD' AND confirmed_at IS NULL"
              + " AND created_at < :cutoff",
      nativeQuery = true)
  int expire(@Param("id") UUID id, @Param("cutoff") Instant cutoff, @Param("now") Instant now);

  /** Marks a HELD reservation as kept by its case; 0 rows when closed or already confirmed. */
  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query(
      value =
          "UPDATE capacity.capacity_reservation SET confirmed_at = :now"
              + " WHERE id = :id AND status = 'HELD' AND confirmed_at IS NULL",
      nativeQuery = true)
  int confirm(@Param("id") UUID id, @Param("now") Instant now);

  /** The oldest unconfirmed holds past the cutoff. */
  @Query(
      value =
          "SELECT * FROM capacity.capacity_reservation WHERE status = 'HELD'"
              + " AND confirmed_at IS NULL AND created_at < :cutoff ORDER BY created_at LIMIT :limit",
      nativeQuery = true)
  List<CapacityReservationEntity> findExpirable(
      @Param("cutoff") Instant cutoff, @Param("limit") int limit);
}
