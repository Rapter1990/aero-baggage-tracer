package com.aerotrace.identity.model.entity;

import com.aerotrace.common.security.Role;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Persistable;

/**
 * An application user. The id is assigned by the application (a random UUID), so the entity says it
 * is new until it has been stored, which lets Spring Data insert it without a lookup first.
 *
 * <p>The password is only ever a salted hash. The passenger reference is a pseudonym ("P-" plus 20
 * digits), never an email address or name.
 */
@Entity
@Table(name = "app_user", schema = "identity")
public class AppUserEntity implements Persistable<UUID> {

  @Id private UUID id;

  @Column(nullable = false, unique = true, length = 64)
  private String username;

  @Column(name = "password_hash", nullable = false, length = 100)
  private String passwordHash;

  @Column(name = "passenger_ref", unique = true, length = 22)
  private String passengerRef;

  @Column(nullable = false)
  private boolean enabled = true;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @ElementCollection(fetch = FetchType.EAGER)
  @CollectionTable(
      name = "user_role",
      schema = "identity",
      joinColumns = @JoinColumn(name = "user_id"))
  @Column(name = "role", nullable = false, length = 20)
  @Enumerated(EnumType.STRING)
  private Set<Role> roles = new HashSet<>();

  @Transient private boolean isNew = true;

  protected AppUserEntity() {}

  public AppUserEntity(
      UUID id, String username, String passwordHash, String passengerRef, Set<Role> roles) {
    this.id = id;
    this.username = username;
    this.passwordHash = passwordHash;
    this.passengerRef = passengerRef;
    this.roles = new HashSet<>(roles);
  }

  @PrePersist
  void prePersist() {
    if (createdAt == null) {
      createdAt = Instant.now();
    }
  }

  @PostLoad
  @PostPersist
  void markStored() {
    isNew = false;
  }

  @Override
  public UUID getId() {
    return id;
  }

  @Override
  public boolean isNew() {
    return isNew;
  }

  public String getUsername() {
    return username;
  }

  public String getPasswordHash() {
    return passwordHash;
  }

  public String getPassengerRef() {
    return passengerRef;
  }

  public boolean isEnabled() {
    return enabled;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Set<Role> getRoles() {
    return roles;
  }
}
