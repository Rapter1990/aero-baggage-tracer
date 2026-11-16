package com.aerotrace.journey.model.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.UuidGenerator;

/**
 * One accepted scan. The table is append-only (database triggers refuse changes). Times are UTC;
 * the timestamp of the device and the zone it reported are kept. {@code applied} says whether the
 * scan became the latest state (false for a scan older than the current state).
 */
@Entity
@Immutable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "scan_audit", schema = "journey")
public class ScanAuditEntity {

  @Id @GeneratedValue @UuidGenerator private UUID id;

  @Column(name = "event_id", nullable = false, updatable = false, length = 128)
  private String eventId;

  @Column(name = "bag_tag", nullable = false, updatable = false, length = 10)
  private String bagTag;

  @Column(nullable = false, updatable = false, length = 12)
  private String protocol;

  @Column(nullable = false, updatable = false, length = 3)
  private String airport;

  @Column(nullable = false, updatable = false, length = 20)
  private String checkpoint;

  @Column(name = "device_sequence", nullable = false, updatable = false)
  private long deviceSequence;

  @Column(name = "occurred_at", nullable = false, updatable = false)
  private Instant occurredAt;

  @Column(name = "device_timestamp", nullable = false, updatable = false)
  private Instant deviceTimestamp;

  @Column(name = "source_timezone", nullable = false, updatable = false, length = 40)
  private String sourceTimezone;

  @Column(name = "ingested_at", nullable = false, updatable = false)
  private Instant ingestedAt;

  @Column(name = "late_event", nullable = false, updatable = false)
  private boolean lateEvent;

  @Column(nullable = false, updatable = false)
  private boolean applied;

  public ScanAuditEntity(
      String eventId,
      String bagTag,
      String protocol,
      String airport,
      String checkpoint,
      long deviceSequence,
      Instant occurredAt,
      Instant deviceTimestamp,
      String sourceTimezone,
      Instant ingestedAt,
      boolean lateEvent,
      boolean applied) {
    this.eventId = eventId;
    this.bagTag = bagTag;
    this.protocol = protocol;
    this.airport = airport;
    this.checkpoint = checkpoint;
    this.deviceSequence = deviceSequence;
    this.occurredAt = occurredAt;
    this.deviceTimestamp = deviceTimestamp;
    this.sourceTimezone = sourceTimezone;
    this.ingestedAt = ingestedAt;
    this.lateEvent = lateEvent;
    this.applied = applied;
  }
}
