package com.aerotrace.notification.constant;

import lombok.experimental.UtilityClass;

/** Names and limits of the case-changed consumer (ADR 0005, decisions 5 and 7). */
@UtilityClass
public class KafkaConstants {

  /** The listener id of the {@code recovery.case.changed.v1} consumer. */
  public static final String LISTENER_ID = "notification-case-changed";

  /** How much of a bad event is kept on the dead-letter topic. */
  public static final int MAX_RAW_PAYLOAD_CHARS = 16_384;

  /** The free-text field that may name people or places; never copied to a dead letter. */
  public static final String REASON_FIELD = "reason";
}
