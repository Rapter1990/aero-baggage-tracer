package com.aerotrace.journey.repository;

import java.sql.Timestamp;
import java.time.Instant;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Durable dedupe of event ids; the primary key decides, so replicas cannot both win. */
@Repository
public class ProcessedEventRepository {

  private final JdbcClient jdbc;

  public ProcessedEventRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /** Records the id; false when it was recorded before (a duplicate). */
  public boolean markProcessed(String eventId, Instant at) {
    return jdbc.sql(
                "INSERT INTO journey.processed_event (event_id, processed_at) VALUES (:id, :at)"
                    + " ON CONFLICT (event_id) DO NOTHING")
            .param("id", eventId)
            .param("at", Timestamp.from(at))
            .update()
        == 1;
  }
}
