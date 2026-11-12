package com.aerotrace.identity.model.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Immutable;

/**
 * One authentication event. The table is append-only (database triggers refuse changes) and has no
 * column for a password, token or any other secret, so none can be stored by mistake.
 */
@Entity
@Immutable
@Table(name = "auth_audit", schema = "identity")
public class AuthAuditEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "occurred_at", nullable = false, updatable = false)
  private Instant occurredAt;

  @Column(name = "event_type", nullable = false, updatable = false, length = 40)
  private String eventType;

  @Column(nullable = false, updatable = false, length = 10)
  private String outcome;

  @Column(updatable = false, length = 128)
  private String username;

  @Column(name = "user_id", updatable = false)
  private UUID userId;

  protected AuthAuditEntity() {}

  public AuthAuditEntity(
      Instant occurredAt, String eventType, String outcome, String username, UUID userId) {
    this.occurredAt = occurredAt;
    this.eventType = eventType;
    this.outcome = outcome;
    this.username = username;
    this.userId = userId;
  }

  public Long getId() {
    return id;
  }

  public Instant getOccurredAt() {
    return occurredAt;
  }

  public String getEventType() {
    return eventType;
  }

  public String getOutcome() {
    return outcome;
  }

  public String getUsername() {
    return username;
  }

  public UUID getUserId() {
    return userId;
  }
}
