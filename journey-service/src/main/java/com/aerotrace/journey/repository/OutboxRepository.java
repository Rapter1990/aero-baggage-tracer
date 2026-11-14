package com.aerotrace.journey.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** The transactional outbox: events stored with their cause, published later by the relay. */
@Repository
public class OutboxRepository {

  /** An event waiting to be published. */
  public record Row(UUID id, String topic, String key, String payload) {}

  private final JdbcClient jdbc;

  public OutboxRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public void add(UUID id, String topic, String key, String payload, Instant at) {
    jdbc.sql(
            "INSERT INTO journey.outbox_event (id, topic, message_key, payload, created_at)"
                + " VALUES (:id, :topic, :key, :payload, :at)")
        .param("id", id)
        .param("topic", topic)
        .param("key", key)
        .param("payload", payload)
        .param("at", Timestamp.from(at))
        .update();
  }

  /** Arbitrary but fixed number of the advisory lock that lets one relay run at a time. */
  private static final long RELAY_LOCK = 7_410_001L;

  /**
   * Takes the relay lock until the end of the transaction. Only one relay runs at a time, in any
   * replica, so the events of a bag leave in the order they were stored; the others skip the run.
   */
  public boolean tryLockRelay() {
    return Boolean.TRUE.equals(
        jdbc.sql("SELECT pg_try_advisory_xact_lock(:key)")
            .param("key", RELAY_LOCK)
            .query(Boolean.class)
            .single());
  }

  /**
   * Takes the oldest unpublished rows and locks them until the transaction ends. Rows another
   * replica has locked are skipped, so two relays never publish the same row at the same time.
   */
  public List<Row> claimPending(int limit) {
    return jdbc.sql(
            "SELECT id, topic, message_key, payload FROM journey.outbox_event"
                + " WHERE published_at IS NULL AND parked_at IS NULL ORDER BY created_at, id LIMIT :limit"
                + " FOR UPDATE SKIP LOCKED")
        .param("limit", limit)
        .query(
            (rs, i) ->
                new Row(
                    rs.getObject("id", UUID.class),
                    rs.getString("topic"),
                    rs.getString("message_key"),
                    rs.getString("payload")))
        .list();
  }

  public void markPublished(UUID id, Instant at) {
    jdbc.sql(
            "UPDATE journey.outbox_event SET published_at = :at, attempts = attempts + 1"
                + " WHERE id = :id")
        .param("at", Timestamp.from(at))
        .param("id", id)
        .update();
  }

  /**
   * Sets a row aside for good: the broker will never accept it, so retrying only blocks the rest.
   */
  public void park(UUID id, Instant at) {
    jdbc.sql(
            "UPDATE journey.outbox_event SET parked_at = :at, attempts = attempts + 1"
                + " WHERE id = :id")
        .param("at", Timestamp.from(at))
        .param("id", id)
        .update();
  }

  public void recordFailure(UUID id) {
    jdbc.sql("UPDATE journey.outbox_event SET attempts = attempts + 1 WHERE id = :id")
        .param("id", id)
        .update();
  }

  public int deletePublishedBefore(Instant cutoff) {
    return jdbc.sql(
            "DELETE FROM journey.outbox_event WHERE published_at IS NOT NULL"
                + " AND published_at < :cutoff")
        .param("cutoff", Timestamp.from(cutoff))
        .update();
  }
}
