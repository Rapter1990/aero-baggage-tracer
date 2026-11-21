package com.aerotrace.notification.repository;

import com.aerotrace.notification.model.entity.NotificationEntity;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface NotificationRepository extends JpaRepository<NotificationEntity, UUID> {

  /**
   * Stores a new pending notification, created and due at the database's {@code now()}, unless one
   * exists for the same {@code (recipient, eventId, template)}. The unique constraint decides, so
   * two replicas racing on one event leave one row (BR-08, AT-13).
   *
   * @param state the case state name, or null for a state this service does not know
   * @return 1 when the row was written, 0 when it already existed (a duplicate, also a success)
   */
  @Modifying
  @Transactional
  @Query(
      value =
          "INSERT INTO notification.notification (id, recipient, event_id, template, case_id,"
              + " bag_tag, state, case_version, occurred_at, correlation_id, status, attempts,"
              + " next_attempt_at, created_at, version)"
              + " VALUES (:id, :recipient, :eventId, :template, :caseId, :bagTag, :state,"
              + " :caseVersion, :occurredAt, :correlationId, 'PENDING', 0, now(), now(), 0)"
              + " ON CONFLICT ON CONSTRAINT uq_notification_recipient_event_template DO NOTHING",
      nativeQuery = true)
  int insertIfAbsent(
      @Param("id") UUID id,
      @Param("recipient") String recipient,
      @Param("eventId") String eventId,
      @Param("template") String template,
      @Param("caseId") UUID caseId,
      @Param("bagTag") String bagTag,
      @Param("state") String state,
      @Param("caseVersion") long caseVersion,
      @Param("occurredAt") Instant occurredAt,
      @Param("correlationId") String correlationId);

  /** A user's own notifications; the caller orders by created_at and id, both descending. */
  Page<NotificationEntity> findByRecipient(String recipient, Pageable pageable);

  /** One notification, only if it belongs to {@code recipient}. */
  Optional<NotificationEntity> findByIdAndRecipient(UUID id, String recipient);
}
