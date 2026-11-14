package com.aerotrace.identity.auth;

import com.aerotrace.identity.util.TokenHashUtils;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Counts failed sign-ins and password reset requests per typed name, and locks a name for a limited
 * time when it gets too many. The counters live in PostgreSQL, so every instance of the service
 * sees the same numbers and a restart forgets nothing.
 *
 * <p>The key is a hash of the scope and the typed text: the text itself is never stored (people
 * paste passwords into the username box). Unknown names are counted like real ones, so a locked
 * name looks the same whether or not the account exists.
 *
 * <p>Time is passed in by the caller, so it can come from the service's single clock.
 */
@Component
public class LoginThrottle {

  /** What is being counted. Each scope has its own counters. */
  public enum Scope {
    LOGIN,
    PASSWORD_RESET
  }

  /**
   * The result of counting one hit.
   *
   * @param lockedNow true when this very hit started a lock
   * @param lockedUntil when the lock ends, or null when the name is not locked
   */
  public record Hit(boolean lockedNow, Instant lockedUntil) {}

  private record Row(int hits, Instant windowStartedAt, Instant lockedUntil) {}

  private final JdbcClient jdbc;
  private final TransactionOperations transactions;

  public LoginThrottle(JdbcClient jdbc, TransactionOperations transactions) {
    this.jdbc = jdbc;
    this.transactions = transactions;
  }

  /** The stored key: a hash, so the typed name never reaches the table. */
  static String keyHash(Scope scope, String key) {
    return TokenHashUtils.sha256(scope.name() + ":" + key);
  }

  /** When the lock on this name ends, or empty when it is not locked at {@code now}. */
  public Optional<Instant> lockedUntil(Scope scope, String key, Instant now) {
    return jdbc.sql(
            "SELECT locked_until FROM identity.login_throttle "
                + "WHERE key_hash = :h AND locked_until > :now")
        .param("h", keyHash(scope, key))
        .param("now", utc(now))
        .query(OffsetDateTime.class)
        .optional()
        .map(OffsetDateTime::toInstant);
  }

  /**
   * Counts one hit. Hits further apart than {@code window} do not add up. The {@code threshold}-th
   * hit locks the name for {@code lockFor} and starts a fresh count. While a lock runs, hits are
   * neither counted nor able to extend it, so trying again and again cannot keep a name locked.
   *
   * <p>The row is read with a lock, so concurrent hits for one name are counted one after the other
   * and exactly one of them starts the lock.
   */
  public Hit recordHit(
      Scope scope, String key, Instant now, int threshold, Duration window, Duration lockFor) {
    String hash = keyHash(scope, key);
    return transactions.execute(status -> count(hash, now, threshold, window, lockFor));
  }

  private Hit count(String hash, Instant now, int threshold, Duration window, Duration lockFor) {
    jdbc.sql(
            "INSERT INTO identity.login_throttle (key_hash, hits, window_started_at) "
                + "VALUES (:h, 0, :now) ON CONFLICT (key_hash) DO NOTHING")
        .param("h", hash)
        .param("now", utc(now))
        .update();
    Row row =
        jdbc.sql(
                "SELECT hits, window_started_at, locked_until FROM identity.login_throttle "
                    + "WHERE key_hash = :h FOR UPDATE")
            .param("h", hash)
            .query(
                (rs, i) -> {
                  OffsetDateTime lock = rs.getObject(3, OffsetDateTime.class);
                  return new Row(
                      rs.getInt(1),
                      rs.getObject(2, OffsetDateTime.class).toInstant(),
                      lock == null ? null : lock.toInstant());
                })
            .single();

    if (row.lockedUntil() != null && row.lockedUntil().isAfter(now)) {
      return new Hit(false, row.lockedUntil());
    }
    int hits = row.hits();
    Instant windowStart = row.windowStartedAt();
    if (!windowStart.plus(window).isAfter(now)) {
      hits = 0;
      windowStart = now;
    }
    hits++;
    boolean locks = hits >= threshold;
    Instant lockedUntil = null;
    if (locks) {
      lockedUntil = now.plus(lockFor);
      hits = 0;
      windowStart = now;
    }
    jdbc.sql(
            "UPDATE identity.login_throttle "
                + "SET hits = :hits, window_started_at = :started, locked_until = :locked "
                + "WHERE key_hash = :h")
        .param("hits", hits)
        .param("started", utc(windowStart))
        .param(
            "locked",
            lockedUntil == null ? null : utc(lockedUntil),
            java.sql.Types.TIMESTAMP_WITH_TIMEZONE)
        .param("h", hash)
        .update();
    return new Hit(locks, lockedUntil);
  }

  /** Forgets the counter, for example after a successful sign-in. */
  public void clear(Scope scope, String key) {
    jdbc.sql("DELETE FROM identity.login_throttle WHERE key_hash = :h")
        .param("h", keyHash(scope, key))
        .update();
  }

  /**
   * Deletes counters that last changed before {@code cutoff} and whose lock, if any, has ended at
   * {@code now}. Several instances can run it at once: the delete is idempotent.
   */
  public int purge(Instant cutoff, Instant now) {
    return jdbc.sql(
            "DELETE FROM identity.login_throttle WHERE window_started_at < :cutoff "
                + "AND (locked_until IS NULL OR locked_until <= :now)")
        .param("cutoff", utc(cutoff))
        .param("now", utc(now))
        .update();
  }

  private static OffsetDateTime utc(Instant instant) {
    return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
  }
}
