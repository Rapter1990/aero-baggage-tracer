package com.aerotrace.notification.repository;

import com.aerotrace.common.model.RecoveryState;
import com.aerotrace.notification.constant.NotificationRules;
import com.aerotrace.notification.model.entity.NotificationEntity;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/**
 * The delivery statements: claim, finish, lease sweep, dead-letter claim and the retention purge
 * (ADR 0005, decisions 9, 10 and 14), as Spring Data JPA native queries on {@link
 * NotificationEntity}. JPQL cannot express them: the claim is {@code UPDATE ... RETURNING} over a
 * {@code FOR UPDATE SKIP LOCKED} subquery, and every time must be the database's {@code now()}.
 * Each is one statement in its own short transaction, so no lock is ever held while the provider or
 * Kafka is called. {@code next_attempt_at} is both the due time and the lease deadline of a claim;
 * {@code version} guards every finish, so the result of a replica whose lease was taken over is
 * dropped (the entity's {@code @Version} is not involved: native statements raise {@code version}
 * themselves, and the database trigger insists on it).
 *
 * <p>Every time is the database's {@code now()}, never a replica's clock: replicas whose clocks
 * drift apart still agree on what is due and when a lease ends. Waits (lease, backoff, retention)
 * are passed as seconds and added in the statement; the default methods take a {@link Duration}.
 * This repository exposes no CRUD: {@link NotificationRepository} reads and inserts.
 */
@Transactional
public interface DeliveryRepository extends Repository<NotificationEntity, UUID> {

  /** A notification claimed for one delivery attempt; {@code attempts} already counts it. */
  record Claim(
      UUID id,
      String recipient,
      String template,
      UUID caseId,
      String bagTag,
      RecoveryState state,
      long caseVersion,
      int attempts,
      long version,
      String correlationId) {

    @Override
    public String toString() {
      return "Claim[id=" + id + ", template=" + template + ", attempts=" + attempts + "]";
    }
  }

  /** A FAILED notification claimed for its dead letter. */
  record DeadLetterClaim(
      UUID id,
      String eventId,
      int attempts,
      Instant failedAt,
      long version,
      String correlationId) {}

  /** The columns a claim returns (a projection of the native result). */
  interface ClaimRow {
    UUID getId();

    String getRecipient();

    String getTemplate();

    UUID getCaseId();

    String getBagTag();

    String getState();

    Long getCaseVersion();

    Integer getAttempts();

    Long getVersion();

    String getCorrelationId();
  }

  /** The columns a dead-letter claim returns. */
  interface DeadLetterRow {
    UUID getId();

    String getEventId();

    Integer getAttempts();

    Instant getFailedAt();

    Long getVersion();

    String getCorrelationId();
  }

  // ------------------------------------------------------------------ claim

  /**
   * Claims up to {@code batch} due PENDING rows: each gets {@code next_attempt_at = now() + lease}
   * (the lease), one more attempt and a new version. Rows another replica has locked are skipped.
   */
  default List<Claim> claimDue(Duration lease, int maxAttempts, int batch) {
    return claimDueRows(seconds(lease), maxAttempts, batch).stream()
        .map(
            row ->
                new Claim(
                    row.getId(),
                    row.getRecipient(),
                    row.getTemplate(),
                    row.getCaseId(),
                    row.getBagTag(),
                    row.getState() == null ? null : RecoveryState.valueOf(row.getState()),
                    row.getCaseVersion(),
                    row.getAttempts(),
                    row.getVersion(),
                    row.getCorrelationId()))
        .toList();
  }

  @Query(
      value =
          "UPDATE notification.notification SET"
              + " next_attempt_at = now() + make_interval(secs => CAST(:lease AS double precision)),"
              + " attempts = attempts + 1, version = version + 1"
              + " WHERE id IN (SELECT id FROM notification.notification"
              + " WHERE status = 'PENDING' AND next_attempt_at <= now() AND attempts < :max"
              + " ORDER BY next_attempt_at LIMIT :batch FOR UPDATE SKIP LOCKED)"
              + " RETURNING id AS \"id\", recipient AS \"recipient\", template AS \"template\","
              + " case_id AS \"caseId\", bag_tag AS \"bagTag\", state AS \"state\","
              + " case_version AS \"caseVersion\", attempts AS \"attempts\","
              + " version AS \"version\", correlation_id AS \"correlationId\"",
      nativeQuery = true)
  List<ClaimRow> claimDueRows(
      @Param("lease") double leaseSeconds,
      @Param("max") int maxAttempts,
      @Param("batch") int batch);

  /** Claims up to {@code batch} FAILED rows whose dead letter Kafka has not acknowledged yet. */
  default List<DeadLetterClaim> claimDeadLetters(Duration lease, int batch) {
    return claimDeadLetterRows(seconds(lease), batch).stream()
        .map(
            row ->
                new DeadLetterClaim(
                    row.getId(),
                    row.getEventId(),
                    row.getAttempts(),
                    row.getFailedAt(),
                    row.getVersion(),
                    row.getCorrelationId()))
        .toList();
  }

  @Query(
      value =
          "UPDATE notification.notification SET"
              + " next_attempt_at = now() + make_interval(secs => CAST(:lease AS double precision)),"
              + " version = version + 1"
              + " WHERE id IN (SELECT id FROM notification.notification"
              + " WHERE status = 'FAILED' AND dead_lettered_at IS NULL AND next_attempt_at <= now()"
              + " ORDER BY next_attempt_at LIMIT :batch FOR UPDATE SKIP LOCKED)"
              + " RETURNING id AS \"id\", event_id AS \"eventId\", attempts AS \"attempts\","
              + " failed_at AS \"failedAt\", version AS \"version\","
              + " correlation_id AS \"correlationId\"",
      nativeQuery = true)
  List<DeadLetterRow> claimDeadLetterRows(
      @Param("lease") double leaseSeconds, @Param("batch") int batch);

  // ------------------------------------------------------------------ finish (guarded by version)

  /** The attempt worked. Returns 0 when the claim was lost to another replica. */
  @Modifying
  @Query(
      value =
          "UPDATE notification.notification SET status = 'SENT', sent_at = now(),"
              + " last_error = NULL, version = version + 1"
              + " WHERE id = :id AND status = 'PENDING' AND version = :version",
      nativeQuery = true)
  int markSent(@Param("id") UUID id, @Param("version") long claimedVersion);

  /**
   * The attempt was cut short by a shutdown: the row is due now and the attempt is given back, so a
   * deploy never costs a row one of its attempts.
   */
  @Modifying
  @Query(
      value =
          "UPDATE notification.notification SET next_attempt_at = now(),"
              + " attempts = GREATEST(attempts - 1, 0), version = version + 1"
              + " WHERE id = :id AND status = 'PENDING' AND version = :version",
      nativeQuery = true)
  int release(@Param("id") UUID id, @Param("version") long claimedVersion);

  /** The attempt failed and another one is due {@code delay} from now. */
  default int markRetry(UUID id, long claimedVersion, Duration delay, String error) {
    return retryAfter(id, claimedVersion, seconds(delay), cut(error));
  }

  @Modifying
  @Query(
      value =
          "UPDATE notification.notification SET"
              + " next_attempt_at = now() + make_interval(secs => CAST(:delay AS double precision)),"
              + " last_error = :error, version = version + 1"
              + " WHERE id = :id AND status = 'PENDING' AND version = :version",
      nativeQuery = true)
  int retryAfter(
      @Param("id") UUID id,
      @Param("version") long claimedVersion,
      @Param("delay") double delaySeconds,
      @Param("error") String error);

  /**
   * The last attempt failed: the row is FAILED, and its dead letter is due at once ({@code
   * next_attempt_at = now()}).
   */
  default int markFailed(UUID id, long claimedVersion, String error) {
    return failAfterLastAttempt(id, claimedVersion, cut(error));
  }

  @Modifying
  @Query(
      value =
          "UPDATE notification.notification SET status = 'FAILED', failed_at = now(),"
              + " next_attempt_at = now(), last_error = :error, version = version + 1"
              + " WHERE id = :id AND status = 'PENDING' AND version = :version",
      nativeQuery = true)
  int failAfterLastAttempt(
      @Param("id") UUID id, @Param("version") long claimedVersion, @Param("error") String error);

  /**
   * PENDING rows whose lease expired after their last attempt (the replica crashed or hung) are
   * FAILED with {@code LEASE_EXPIRED}; their attempt was counted at claim.
   */
  @Modifying
  @Query(
      value =
          "UPDATE notification.notification SET status = 'FAILED', failed_at = now(),"
              + " next_attempt_at = now(), last_error = '"
              + NotificationRules.LEASE_EXPIRED
              + "', version = version + 1"
              + " WHERE status = 'PENDING' AND attempts >= :max AND next_attempt_at <= now()",
      nativeQuery = true)
  int expireLeases(@Param("max") int maxAttempts);

  /** Kafka acknowledged the dead letter. Returns 0 when the claim was lost to another replica. */
  @Modifying
  @Query(
      value =
          "UPDATE notification.notification SET dead_lettered_at = now(), version = version + 1"
              + " WHERE id = :id AND status = 'FAILED' AND dead_lettered_at IS NULL"
              + " AND version = :version",
      nativeQuery = true)
  int markDeadLettered(@Param("id") UUID id, @Param("version") long claimedVersion);

  // ------------------------------------------------------------------ retention and gauges

  /**
   * Deletes up to {@code batch} finished rows (SENT, or FAILED and dead-lettered) created more than
   * {@code retention} ago, oldest first. PENDING rows and undelivered dead letters are never
   * purged.
   */
  default int purgeFinished(Duration retention, int batch) {
    return deleteFinished(seconds(retention), batch);
  }

  @Modifying
  @Query(
      value =
          "DELETE FROM notification.notification WHERE id IN (SELECT id FROM"
              + " notification.notification WHERE created_at < now() -"
              + " make_interval(secs => CAST(:retention AS double precision))"
              + " AND (status = 'SENT' OR (status = 'FAILED' AND dead_lettered_at IS NOT NULL))"
              + " ORDER BY created_at LIMIT :batch FOR UPDATE SKIP LOCKED)",
      nativeQuery = true)
  int deleteFinished(@Param("retention") double retentionSeconds, @Param("batch") int batch);

  /** Notifications not sent yet. */
  @Transactional(readOnly = true)
  @Query(
      value = "SELECT count(*) FROM notification.notification WHERE status = 'PENDING'",
      nativeQuery = true)
  long countPending();

  /** Seconds the oldest due PENDING row has waited past its due time; 0 when none is due. */
  @Transactional(readOnly = true)
  @Query(
      value =
          "SELECT CAST(COALESCE(EXTRACT(EPOCH FROM now() - min(next_attempt_at)), 0)"
              + " AS double precision) FROM notification.notification"
              + " WHERE status = 'PENDING' AND next_attempt_at <= now()",
      nativeQuery = true)
  double oldestDueAgeSeconds();

  // ------------------------------------------------------------------ helpers

  /** A duration as the seconds {@code make_interval} takes (microseconds are kept). */
  private static double seconds(Duration duration) {
    return duration.getSeconds() + duration.getNano() / 1_000_000_000.0;
  }

  /** An error code or class name, cut to what the column holds. */
  private static String cut(String error) {
    if (error == null || error.length() <= NotificationRules.MAX_ERROR_LENGTH) {
      return error;
    }
    return error.substring(0, NotificationRules.MAX_ERROR_LENGTH);
  }
}
