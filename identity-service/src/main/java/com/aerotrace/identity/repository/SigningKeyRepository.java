package com.aerotrace.identity.repository;

import com.aerotrace.identity.model.entity.SigningKeyEntity;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/** Access to signing keys. Every time-dependent query takes {@code now} from the caller. */
public interface SigningKeyRepository extends JpaRepository<SigningKeyEntity, String> {

  /**
   * Serializes key changes across replicas until the surrounding transaction ends. Uses the
   * two-number form of the advisory lock so the id lives in a namespace of this service instead of
   * being a bare number every service in the shared database could pick.
   */
  @Query(
      value = "select 1 from (select pg_advisory_xact_lock(:namespace, :id)) as locked",
      nativeQuery = true)
  List<Integer> lock(@Param("namespace") int namespace, @Param("id") int id);

  /** Keys still published (not retired), including ones that have not started signing yet. */
  @Query(
      "select k from SigningKeyEntity k where k.retireAt is null or k.retireAt > :now order by k.activateAt")
  List<SigningKeyEntity> findPublished(@Param("now") Instant now);

  @Query("select count(k) from SigningKeyEntity k where k.retireAt is null or k.retireAt > :now")
  long countPublished(@Param("now") Instant now);

  /**
   * Candidates for signing now: activated, not retired, still holding a private key; newest first.
   */
  @Query(
      "select k from SigningKeyEntity k where k.activateAt <= :now "
          + "and (k.retireAt is null or k.retireAt > :now) and k.privateKeyEnc is not null "
          + "order by k.activateAt desc")
  List<SigningKeyEntity> findSigning(@Param("now") Instant now, Limit limit);

  /** A key that has been published but has not started signing yet, newest first. */
  @Query("select k from SigningKeyEntity k where k.activateAt > :now order by k.activateAt desc")
  List<SigningKeyEntity> findPending(@Param("now") Instant now, Limit limit);

  @Query("select max(k.activateAt) from SigningKeyEntity k")
  Optional<Instant> newestActivation();

  /** Marks every key without a retirement yet, except the new one, to retire at that time. */
  @Transactional
  @Modifying(clearAutomatically = true)
  @Query(
      "update SigningKeyEntity k set k.retireAt = :retireAt where k.retireAt is null and k.kid <> :kid")
  int scheduleRetirement(@Param("retireAt") Instant retireAt, @Param("kid") String exceptKid);

  /** Erases the private key of every retired key. Returns how many were erased. */
  @Transactional
  @Modifying(clearAutomatically = true)
  @Query(
      "update SigningKeyEntity k set k.privateKeyEnc = null where k.retireAt <= :now and k.privateKeyEnc is not null")
  int eraseRetiredPrivateKeys(@Param("now") Instant now);

  /** How many keys were created at or after the given moment. */
  @Query("select count(k) from SigningKeyEntity k where k.createdAt >= :since")
  long countCreatedSince(@Param("since") Instant since);

  /** Sets the retirement of the given keys (those that cannot be read), unless already earlier. */
  @Transactional
  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query(
      "update SigningKeyEntity k set k.retireAt = :retireAt "
          + "where k.kid in :kids and (k.retireAt is null or k.retireAt > :retireAt)")
  int retireKeys(@Param("kids") Collection<String> kids, @Param("retireAt") Instant retireAt);
}
