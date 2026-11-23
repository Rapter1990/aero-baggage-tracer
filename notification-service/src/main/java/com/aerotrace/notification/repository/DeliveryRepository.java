package com.aerotrace.notification.repository;

import com.aerotrace.common.model.RecoveryState;
import com.aerotrace.notification.constant.NotificationRules;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * The delivery statements: claim, finish, lease sweep, dead-letter claim and the retention purge
 * (ADR 0005, decisions 9, 10 and 14). Each is one statement in its own short transaction, so no
 * lock is ever held while the provider or Kafka is called. {@code next_attempt_at} is both the due
 * time and the lease deadline of a claim; {@code version} guards every finish, so the result of a
 * replica whose lease was taken over is dropped.
 *
 * <p>Every time is the database's {@code now()}, never a replica's clock: replicas whose clocks
 * drift apart still agree on what is due and when a lease ends. Waits (lease, backoff, retention)
 * are passed as durations and added in the statement.
 */
@Repository
public class DeliveryRepository {

  private static final String TABLE = "notification.notification";

  private final NamedParameterJdbcTemplate jdbc;

  public DeliveryRepository(NamedParameterJdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** A notification claimed for one delivery attempt; {@code attempts} already counts it. */
  public record Claim(
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
  public record DeadLetterClaim(
      UUID id,
      String eventId,
      int attempts,
      Instant failedAt,
      long version,
      String correlationId) {}

  /**
   * Claims up to {@code batch} due PENDING rows: each gets {@code next_attempt_at = now() + lease}
   * (the lease), one more attempt and a new version. Rows another replica has locked are skipped.
   */
  public List<Claim> claimDue(Duration lease, int maxAttempts, int batch) {
    return jdbc.query(
        "UPDATE "
            + TABLE
            + " SET next_attempt_at = "
            + later("lease")
            + ", attempts = attempts + 1, version = version + 1"
            + " WHERE id IN (SELECT id FROM "
            + TABLE
            + " WHERE status = 'PENDING' AND next_attempt_at <= now() AND attempts < :max"
            + " ORDER BY next_attempt_at LIMIT :batch FOR UPDATE SKIP LOCKED)"
            + " RETURNING id, recipient, template, case_id, bag_tag, state, case_version,"
            + " attempts, version, correlation_id",
        new MapSqlParameterSource()
            .addValue("lease", seconds(lease))
            .addValue("max", maxAttempts)
            .addValue("batch", batch),
        (rs, row) ->
            new Claim(
                rs.getObject("id", UUID.class),
                rs.getString("recipient"),
                rs.getString("template"),
                rs.getObject("case_id", UUID.class),
                rs.getString("bag_tag"),
                state(rs),
                rs.getLong("case_version"),
                rs.getInt("attempts"),
                rs.getLong("version"),
                rs.getString("correlation_id")));
  }

  /** The attempt worked. Returns 0 when the claim was lost to another replica. */
  public int markSent(UUID id, long claimedVersion) {
    return jdbc.update(
        "UPDATE "
            + TABLE
            + " SET status = 'SENT', sent_at = now(), last_error = NULL, version = version + 1"
            + " WHERE id = :id AND status = 'PENDING' AND version = :version",
        guard(id, claimedVersion));
  }

  /**
   * The attempt was cut short by a shutdown: the row is due now and the attempt is given back, so a
   * deploy never costs a row one of its attempts.
   */
  public int release(UUID id, long claimedVersion) {
    return jdbc.update(
        "UPDATE "
            + TABLE
            + " SET next_attempt_at = now(), attempts = GREATEST(attempts - 1, 0),"
            + " version = version + 1"
            + " WHERE id = :id AND status = 'PENDING' AND version = :version",
        guard(id, claimedVersion));
  }

  /** The attempt failed and another one is due {@code delay} from now. */
  public int markRetry(UUID id, long claimedVersion, Duration delay, String error) {
    return jdbc.update(
        "UPDATE "
            + TABLE
            + " SET next_attempt_at = "
            + later("delay")
            + ", last_error = :error, version = version + 1"
            + " WHERE id = :id AND status = 'PENDING' AND version = :version",
        guard(id, claimedVersion)
            .addValue("delay", seconds(delay))
            .addValue("error", error(error)));
  }

  /**
   * The last attempt failed: the row is FAILED, and its dead letter is due at once ({@code
   * next_attempt_at = now()}).
   */
  public int markFailed(UUID id, long claimedVersion, String error) {
    return jdbc.update(
        "UPDATE "
            + TABLE
            + " SET status = 'FAILED', failed_at = now(), next_attempt_at = now(),"
            + " last_error = :error, version = version + 1"
            + " WHERE id = :id AND status = 'PENDING' AND version = :version",
        guard(id, claimedVersion).addValue("error", error(error)));
  }

  /**
   * PENDING rows whose lease expired after their last attempt (the replica crashed or hung) are
   * FAILED with {@code LEASE_EXPIRED}; their attempt was counted at claim.
   */
  public int expireLeases(int maxAttempts) {
    return jdbc.update(
        "UPDATE "
            + TABLE
            + " SET status = 'FAILED', failed_at = now(), next_attempt_at = now(),"
            + " last_error = :error, version = version + 1"
            + " WHERE status = 'PENDING' AND attempts >= :max AND next_attempt_at <= now()",
        new MapSqlParameterSource()
            .addValue("max", maxAttempts)
            .addValue("error", NotificationRules.LEASE_EXPIRED));
  }

  /** Claims up to {@code batch} FAILED rows whose dead letter Kafka has not acknowledged yet. */
  public List<DeadLetterClaim> claimDeadLetters(Duration lease, int batch) {
    return jdbc.query(
        "UPDATE "
            + TABLE
            + " SET next_attempt_at = "
            + later("lease")
            + ", version = version + 1"
            + " WHERE id IN (SELECT id FROM "
            + TABLE
            + " WHERE status = 'FAILED' AND dead_lettered_at IS NULL AND next_attempt_at <= now()"
            + " ORDER BY next_attempt_at LIMIT :batch FOR UPDATE SKIP LOCKED)"
            + " RETURNING id, event_id, attempts, failed_at, version, correlation_id",
        new MapSqlParameterSource().addValue("lease", seconds(lease)).addValue("batch", batch),
        (rs, row) ->
            new DeadLetterClaim(
                rs.getObject("id", UUID.class),
                rs.getString("event_id"),
                rs.getInt("attempts"),
                instant(rs, "failed_at"),
                rs.getLong("version"),
                rs.getString("correlation_id")));
  }

  /** Kafka acknowledged the dead letter. Returns 0 when the claim was lost to another replica. */
  public int markDeadLettered(UUID id, long claimedVersion) {
    return jdbc.update(
        "UPDATE "
            + TABLE
            + " SET dead_lettered_at = now(), version = version + 1"
            + " WHERE id = :id AND status = 'FAILED' AND dead_lettered_at IS NULL"
            + " AND version = :version",
        guard(id, claimedVersion));
  }

  /**
   * Deletes up to {@code batch} finished rows (SENT, or FAILED and dead-lettered) created more than
   * {@code retention} ago, oldest first. PENDING rows and undelivered dead letters are never
   * purged.
   */
  public int purgeFinished(Duration retention, int batch) {
    return jdbc.update(
        "DELETE FROM "
            + TABLE
            + " WHERE id IN (SELECT id FROM "
            + TABLE
            + " WHERE created_at < now() - make_interval(secs => CAST(:retention AS double precision))"
            + " AND (status = 'SENT' OR (status = 'FAILED' AND dead_lettered_at IS NOT NULL))"
            + " ORDER BY created_at LIMIT :batch FOR UPDATE SKIP LOCKED)",
        new MapSqlParameterSource()
            .addValue("retention", seconds(retention))
            .addValue("batch", batch));
  }

  /** Notifications not sent yet. */
  public long countPending() {
    Long count =
        jdbc.queryForObject(
            "SELECT count(*) FROM " + TABLE + " WHERE status = 'PENDING'",
            new MapSqlParameterSource(),
            Long.class);
    return count == null ? 0 : count;
  }

  /** Seconds the oldest due PENDING row has waited past its due time; 0 when none is due. */
  public double oldestDueAgeSeconds() {
    Double age =
        jdbc.queryForObject(
            "SELECT CAST(COALESCE(EXTRACT(EPOCH FROM now() - min(next_attempt_at)), 0)"
                + " AS double precision) FROM "
                + TABLE
                + " WHERE status = 'PENDING' AND next_attempt_at <= now()",
            new MapSqlParameterSource(),
            Double.class);
    return age == null ? 0 : age;
  }

  /** {@code now() + :param}, where the parameter is a number of seconds. */
  private static String later(String param) {
    return "now() + make_interval(secs => CAST(:" + param + " AS double precision))";
  }

  /** A duration as the seconds {@code make_interval} takes (microseconds are kept). */
  private static double seconds(Duration duration) {
    return duration.getSeconds() + duration.getNano() / 1_000_000_000.0;
  }

  private static MapSqlParameterSource guard(UUID id, long version) {
    return new MapSqlParameterSource().addValue("id", id).addValue("version", version);
  }

  private static String error(String error) {
    if (error == null || error.length() <= NotificationRules.MAX_ERROR_LENGTH) {
      return error;
    }
    return error.substring(0, NotificationRules.MAX_ERROR_LENGTH);
  }

  private static RecoveryState state(ResultSet rs) throws SQLException {
    String state = rs.getString("state");
    return state == null ? null : RecoveryState.valueOf(state);
  }

  private static Instant instant(ResultSet rs, String column) throws SQLException {
    OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
    return value == null ? null : value.toInstant();
  }
}
