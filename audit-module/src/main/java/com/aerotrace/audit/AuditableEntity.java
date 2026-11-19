package com.aerotrace.audit;

import jakarta.persistence.Column;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.MappedSuperclass;
import java.time.Instant;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedBy;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * Who created and last changed a row, and when. Extend it and add the four columns to the table
 * (see {@code docs/adr/0003-spring-data-auditing.md}).
 *
 * <p>The values are filled by Spring Data auditing when the entity is saved through JPA: on
 * creation all four are set (so an unchanged row has {@code updatedAt == createdAt}), on a change
 * only the {@code updated*} pair. The user is the subject of the caller's token, see {@link
 * SecurityAuditorAware}. Times are UTC instants, like every other timestamp of the platform.
 *
 * <p>Statements that bypass JPA (a bulk JPQL update, native SQL) do not fire the listener: such a
 * table writes its audit columns in the statement itself.
 */
@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
public abstract class AuditableEntity {

  @CreatedBy
  @Column(name = "created_by", nullable = false, updatable = false, length = 100)
  private String createdBy;

  @CreatedDate
  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @LastModifiedBy
  @Column(name = "updated_by", nullable = false, length = 100)
  private String updatedBy;

  @LastModifiedDate
  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  public String getCreatedBy() {
    return createdBy;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public String getUpdatedBy() {
    return updatedBy;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }
}
