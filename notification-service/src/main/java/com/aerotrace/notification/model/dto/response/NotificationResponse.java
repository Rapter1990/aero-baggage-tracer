package com.aerotrace.notification.model.dto.response;

import com.aerotrace.common.model.RecoveryState;
import com.aerotrace.notification.model.entity.NotificationEntity;
import com.aerotrace.notification.model.entity.NotificationStatus;
import java.time.Instant;
import java.util.UUID;

/**
 * A notification as its recipient reads it. Not in it, on purpose: the recipient (always the
 * caller), the case reason (never stored) and delivery internals such as the next attempt time.
 *
 * @param state null for a state this service did not know when the event arrived
 * @param version the case version of the event
 */
public record NotificationResponse(
    UUID id,
    UUID caseId,
    String bagTag,
    RecoveryState state,
    long version,
    String template,
    NotificationStatus status,
    int attempts,
    Instant createdAt,
    Instant sentAt) {

  public static NotificationResponse of(NotificationEntity n) {
    return new NotificationResponse(
        n.getId(),
        n.getCaseId(),
        n.getBagTag(),
        n.getState(),
        n.getCaseVersion(),
        n.getTemplate(),
        n.getStatus(),
        n.getAttempts(),
        n.getCreatedAt(),
        n.getSentAt());
  }
}
