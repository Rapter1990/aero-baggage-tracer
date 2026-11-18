package com.aerotrace.recovery.model.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * The recovery policy: the source of truth that Redis only caches. It changes only through {@code
 * RecoveryPolicyRepository#change}, a guarded UPDATE on the version.
 */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "recovery_policy", schema = "recovery")
public class RecoveryPolicyEntity {

  @Id
  @Column(name = "policy_key", length = 32)
  private String key;

  @Column(name = "minimum_expedite_minutes", nullable = false)
  private int minimumExpediteMinutes;

  @Column(nullable = false)
  private long version;

  @Column(name = "updated_by", nullable = false, length = 100)
  private String updatedBy;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;
}
