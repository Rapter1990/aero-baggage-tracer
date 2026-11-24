package com.aerotrace.notification.delivery;

import com.aerotrace.common.model.RecoveryState;
import java.util.UUID;

/**
 * What the provider is asked to deliver. {@code id} is the notification id and the idempotency key
 * a real provider must honor: a crash, lost lease or timeout between the send and its record sends
 * again with the same id, and the provider must treat that as the same message, not deliver it
 * twice (ADR 0005, decision 9).
 *
 * @param state null for a state this service does not know (template {@code CASE_UPDATED})
 */
public record OutgoingNotification(
    UUID id,
    String recipient,
    String template,
    UUID caseId,
    String bagTag,
    RecoveryState state,
    long caseVersion) {

  /** The key a provider deduplicates by. */
  public String idempotencyKey() {
    return id.toString();
  }

  /** Without the recipient, so an accidental log line carries no user id. */
  @Override
  public String toString() {
    return "OutgoingNotification[id=" + id + ", template=" + template + ", caseId=" + caseId + "]";
  }
}
