package com.aerotrace.recovery.repository;

import com.aerotrace.recovery.model.entity.ProcessedEventEntity;
import java.time.Instant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Durable dedupe of event ids; the primary key decides, so replicas cannot both win. */
public interface ProcessedEventRepository extends JpaRepository<ProcessedEventEntity, String> {

  /**
   * Records the id and returns 1, or returns 0 when it was recorded before (a duplicate). A plain
   * {@code save} would read first and race; this is one atomic insert, so it is native.
   */
  @Modifying
  @Query(
      value =
          "INSERT INTO recovery.processed_event (event_id, processed_at) VALUES (:id, :at)"
              + " ON CONFLICT (event_id) DO NOTHING",
      nativeQuery = true)
  int insertIfAbsent(@Param("id") String eventId, @Param("at") Instant at);

  default boolean markProcessed(String eventId, Instant at) {
    return insertIfAbsent(eventId, at) == 1;
  }
}
