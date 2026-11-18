package com.aerotrace.recovery.model.entity;

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

/**
 * An event stored in the same transaction as its cause and published to Kafka later by the relay
 * (transactional outbox). A row is pending until {@code publishedAt} is set; a row the broker
 * refuses for good is parked ({@code parkedAt}) and skipped.
 */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "outbox_event", schema = "recovery")
public class OutboxEventEntity {

  @Id @GeneratedValue @UuidGenerator private UUID id;

  @Column(nullable = false, updatable = false, length = 100)
  private String topic;

  @Column(name = "message_key", nullable = false, updatable = false, length = 64)
  private String messageKey;

  @Column(nullable = false, updatable = false, columnDefinition = "text")
  private String payload;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "published_at")
  private Instant publishedAt;

  @Column(nullable = false)
  private int attempts;

  @Column(name = "parked_at")
  private Instant parkedAt;

  public OutboxEventEntity(String topic, String messageKey, String payload, Instant createdAt) {
    this.topic = topic;
    this.messageKey = messageKey;
    this.payload = payload;
    this.createdAt = createdAt;
  }

  /** The broker acknowledged the event. */
  public void markPublished(Instant at) {
    this.publishedAt = at;
    this.attempts++;
  }

  /** A failed try that can be repeated. */
  public void recordFailure() {
    this.attempts++;
  }

  /** The broker will never accept this event: skip it from now on. */
  public void park(Instant at) {
    this.parkedAt = at;
    this.attempts++;
  }
}
