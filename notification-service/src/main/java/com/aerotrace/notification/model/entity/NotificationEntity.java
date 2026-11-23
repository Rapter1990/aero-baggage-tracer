package com.aerotrace.notification.model.entity;

import com.aerotrace.common.model.RecoveryState;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * A notification, read through Spring Data JPA. Rows are written by native queries declared on
 * Spring Data repositories ({@code NotificationRepository}: the insert with {@code ON CONFLICT DO
 * NOTHING}; {@code DeliveryRepository}: the delivery claim, finish, sweep and purge), so the entity
 * has no setters and no auditing (ADR 0005, decision 15). No {@code JdbcTemplate} is used.
 */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "notification", schema = "notification")
public class NotificationEntity {

  @Id private UUID id;

  @Column(nullable = false, updatable = false, length = 100)
  private String recipient;

  @Column(name = "event_id", nullable = false, updatable = false, length = 100)
  private String eventId;

  @Column(nullable = false, updatable = false, length = 40)
  private String template;

  @Column(name = "case_id", nullable = false, updatable = false)
  private UUID caseId;

  @Column(name = "bag_tag", nullable = false, updatable = false, length = 32)
  private String bagTag;

  /** Null for a state this service does not know yet. */
  @Enumerated(EnumType.STRING)
  @Column(updatable = false, length = 32)
  private RecoveryState state;

  @Column(name = "case_version", nullable = false, updatable = false)
  private long caseVersion;

  @Column(name = "occurred_at", nullable = false, updatable = false)
  private Instant occurredAt;

  @Column(name = "correlation_id", updatable = false, length = 64)
  private String correlationId;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 16)
  private NotificationStatus status;

  @Column(nullable = false)
  private int attempts;

  @Column(name = "next_attempt_at", nullable = false)
  private Instant nextAttemptAt;

  @Column(name = "last_error", length = 100)
  private String lastError;

  @Column(name = "sent_at")
  private Instant sentAt;

  @Column(name = "failed_at")
  private Instant failedAt;

  @Column(name = "dead_lettered_at")
  private Instant deadLetteredAt;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Version
  @Column(nullable = false)
  private long version;
}
