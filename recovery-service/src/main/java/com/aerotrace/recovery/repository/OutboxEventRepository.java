package com.aerotrace.recovery.repository;

import com.aerotrace.recovery.model.entity.OutboxEventEntity;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** The transactional outbox: events stored with their cause, published later by the relay. */
public interface OutboxEventRepository extends JpaRepository<OutboxEventEntity, UUID> {

  /** Arbitrary but fixed number of the advisory lock that lets one relay run at a time. */
  long RELAY_LOCK = 7_410_011L;

  /** Takes the relay lock until the end of the transaction; false when another replica has it. */
  @Query(value = "SELECT pg_try_advisory_xact_lock(:key)", nativeQuery = true)
  boolean tryLockRelay(@Param("key") long key);

  /** The oldest pending rows, locked until the transaction ends; locked rows are skipped. */
  @Query(
      value =
          "SELECT * FROM recovery.outbox_event WHERE published_at IS NULL AND parked_at IS NULL"
              + " ORDER BY created_at, id LIMIT :limit FOR UPDATE SKIP LOCKED",
      nativeQuery = true)
  List<OutboxEventEntity> claimPending(@Param("limit") int limit);

  /** How many events wait to be published. */
  @Query(
      "select count(e) from OutboxEventEntity e where e.publishedAt is null and e.parkedAt is null")
  long countPending();

  List<OutboxEventEntity> findByMessageKeyOrderByCreatedAt(String messageKey);

  @Modifying
  @Query(
      "delete from OutboxEventEntity e where e.publishedAt is not null and e.publishedAt < :cutoff")
  int deletePublishedBefore(@Param("cutoff") Instant cutoff);
}
