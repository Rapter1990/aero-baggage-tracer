package com.aerotrace.journey.repository;

import com.aerotrace.journey.model.entity.OutboxEventEntity;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** The transactional outbox: events stored with their cause, published later by the relay. */
public interface OutboxEventRepository extends JpaRepository<OutboxEventEntity, UUID> {

  /** Arbitrary but fixed number of the advisory lock that lets one relay run at a time. */
  long RELAY_LOCK = 7_410_001L;

  /**
   * Takes the relay lock until the end of the transaction. Only one relay runs at a time, in any
   * replica, so the events of a bag leave in the order they were stored; the others skip the run. A
   * database function, so it is native.
   */
  @Query(value = "SELECT pg_try_advisory_xact_lock(:key)", nativeQuery = true)
  boolean tryLockRelay(@Param("key") long key);

  /**
   * Takes the oldest pending rows and locks them until the transaction ends; rows another
   * transaction has locked are skipped. {@code SKIP LOCKED} has no JPQL form, so it is native; the
   * rows come back as managed entities, so changing them is saved when the transaction commits.
   */
  @Query(
      value =
          "SELECT * FROM journey.outbox_event WHERE published_at IS NULL AND parked_at IS NULL"
              + " ORDER BY created_at, id LIMIT :limit FOR UPDATE SKIP LOCKED",
      nativeQuery = true)
  List<OutboxEventEntity> claimPending(@Param("limit") int limit);

  /** How many events wait to be published. */
  @Query(
      "select count(e) from OutboxEventEntity e where e.publishedAt is null and e.parkedAt is null")
  long countPending();

  /** When the oldest waiting event was stored, or empty when nothing waits. */
  @Query(
      "select min(e.createdAt) from OutboxEventEntity e"
          + " where e.publishedAt is null and e.parkedAt is null")
  Optional<Instant> oldestPendingCreatedAt();

  /** The events of one bag, oldest first. */
  List<OutboxEventEntity> findByMessageKeyOrderByCreatedAt(String messageKey);

  @Modifying
  @Query(
      "delete from OutboxEventEntity e where e.publishedAt is not null and e.publishedAt < :cutoff")
  int deletePublishedBefore(@Param("cutoff") Instant cutoff);
}
