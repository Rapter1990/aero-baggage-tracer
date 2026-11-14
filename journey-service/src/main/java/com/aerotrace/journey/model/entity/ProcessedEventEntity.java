package com.aerotrace.journey.model.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

/**
 * An event id that has been handled: the durable dedupe record. Rows are written only by {@code
 * ProcessedEventRepository#markProcessed}, an insert that the primary key decides.
 */
@Entity
@Immutable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "processed_event", schema = "journey")
public class ProcessedEventEntity {

  @Id
  @Column(name = "event_id", length = 128)
  private String eventId;

  @Column(name = "processed_at", nullable = false)
  private Instant processedAt;
}
