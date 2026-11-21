package com.aerotrace.notification.constant;

import java.util.regex.Pattern;
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

  /** {@code last_error} of an attempt the provider did not answer within its timeout. */
  public static final String TIMEOUT = "TIMEOUT";

  /** {@code last_error} of an attempt whose dispatcher thread was interrupted (shutdown). */
  public static final String INTERRUPTED = "INTERRUPTED";

  /** The longest event id, recipient and bag tag a row can hold; longer ones are poison. */
  public static final int MAX_EVENT_ID_LENGTH = 100;

  /**
   * What an event id may be: letters, digits and {@code . _ : -}, 1 to {@value
   * #MAX_EVENT_ID_LENGTH} characters (a UUID, or a prefixed id). Anything else is poison.
   */
  private static final Pattern SAFE_EVENT_ID =
      Pattern.compile("[A-Za-z0-9._:-]{1," + MAX_EVENT_ID_LENGTH + "}");

  public static final int MAX_RECIPIENT_LENGTH = 100;
  public static final int MAX_BAG_TAG_LENGTH = 32;

  /** The longest {@code last_error} stored. */
  public static final int MAX_ERROR_LENGTH = 100;

  public static final int DEFAULT_PAGE_SIZE = 20;
  public static final int MAX_PAGE_SIZE = 100;

  /** Whether {@code eventId} matches {@link #SAFE_EVENT_ID}. */
  public static boolean isSafeEventId(String eventId) {
    return eventId != null && SAFE_EVENT_ID.matcher(eventId).matches();
  }
}
