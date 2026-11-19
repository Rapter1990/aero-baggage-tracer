package com.aerotrace.journey.model.entity;

import com.aerotrace.audit.AuditableEntity;
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
import org.hibernate.annotations.UuidGenerator;

/** A registered bag and its authoritative latest state (the newest accepted scan by event time). */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "baggage", schema = "journey")
public class BaggageEntity extends AuditableEntity {

  @Id @GeneratedValue @UuidGenerator private UUID id;

  @Column(name = "bag_tag", nullable = false, updatable = false, length = 10)
  private String bagTag;

  @Column(name = "passenger_ref", nullable = false, updatable = false, length = 22)
  private String passengerRef;

  @Column(name = "registered_at", nullable = false, updatable = false)
  private Instant registeredAt;

  @Column(name = "latest_scan_id")
  private UUID latestScanId;

  @Column(name = "latest_airport", length = 3)
  private String latestAirport;

  @Column(name = "latest_checkpoint", length = 20)
  private String latestCheckpoint;

  @Column(name = "latest_occurred_at")
  private Instant latestOccurredAt;

  @Column(name = "latest_device_sequence")
  private Long latestDeviceSequence;

  public BaggageEntity(String bagTag, String passengerRef, Instant registeredAt) {
    this.bagTag = bagTag;
    this.passengerRef = passengerRef;
    this.registeredAt = registeredAt;
  }

  /**
   * Whether a scan may become the latest state: a later event time wins, and for the same event
   * time the higher device sequence does, so the answer never depends on arrival order.
   */
  public boolean accepts(Instant occurredAt, long deviceSequence) {
    if (latestOccurredAt == null) {
      return true;
    }
    int byTime = occurredAt.compareTo(latestOccurredAt);
    if (byTime != 0) {
      return byTime > 0;
    }
    return latestDeviceSequence == null || deviceSequence > latestDeviceSequence;
  }

  /** Makes this scan the latest state. The caller checks {@link #accepts} first. */
  public void applyScan(
      UUID scanId, String airport, String checkpoint, Instant occurredAt, long deviceSequence) {
    this.latestScanId = scanId;
    this.latestAirport = airport;
    this.latestCheckpoint = checkpoint;
    this.latestOccurredAt = occurredAt;
    this.latestDeviceSequence = deviceSequence;
  }
}
