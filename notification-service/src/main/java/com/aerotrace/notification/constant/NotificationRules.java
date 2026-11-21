package com.aerotrace.notification.constant;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/** Fixed rules and names of the notification-service (ADR 0005). */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class NotificationRules {

  /** The event type of {@code recovery.case.changed.v1}. */
  public static final String EVENT_TYPE_CASE_CHANGED = "recovery.case.changed";

  /** The dead letters this service publishes: their own event type and schema revision. */
  public static final String EVENT_TYPE_DEAD_LETTER = "baggage.dead-letter";

  public static final int DEAD_LETTER_SCHEMA_VERSION = 1;

  /** The dead-letter reason of a notification whose delivery attempts are used up. */
  public static final String DELIVERY_EXHAUSTED = "DELIVERY_EXHAUSTED";

  /** {@code last_error} of a row whose lease expired after its last attempt (a crashed replica). */
  public static final String LEASE_EXPIRED = "LEASE_EXPIRED";

  /** The longest event id, recipient and bag tag a row can hold; longer ones are poison. */
  public static final int MAX_EVENT_ID_LENGTH = 100;

  public static final int MAX_RECIPIENT_LENGTH = 100;
  public static final int MAX_BAG_TAG_LENGTH = 32;

  /** The longest {@code last_error} stored. */
  public static final int MAX_ERROR_LENGTH = 100;

  public static final int DEFAULT_PAGE_SIZE = 20;
  public static final int MAX_PAGE_SIZE = 100;
}
