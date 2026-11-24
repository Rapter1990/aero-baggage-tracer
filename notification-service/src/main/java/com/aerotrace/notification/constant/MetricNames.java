package com.aerotrace.notification.constant;

import lombok.experimental.UtilityClass;

/** Meter names and tags (ADR 0005, decision 12). */
@UtilityClass
public class MetricNames {

  /**
   * One count per case event read, tagged {@code outcome}: {@code created}, {@code duplicate},
   * {@code unassigned} (no recipient), {@code unknown_state} (a new row with template {@code
   * CASE_UPDATED}) or {@code dead_lettered}.
   */
  public static final String EVENTS = "notification.events";

  /** One count per delivery attempt, tagged {@code outcome}: sent, retry or failed. */
  public static final String DELIVERY = "notification.delivery";

  /** Notifications not sent yet. */
  public static final String PENDING = "notification.pending";

  /** Seconds the oldest due notification has waited past its due time; 0 when none is due. */
  public static final String OLDEST_DUE_AGE = "notification.oldest.due.age";

  public static final String OUTCOME = "outcome";

  public static final String CREATED = "created";
  public static final String DUPLICATE = "duplicate";
  public static final String UNASSIGNED = "unassigned";
  public static final String UNKNOWN_STATE = "unknown_state";
  public static final String DEAD_LETTERED = "dead_lettered";

  public static final String SENT = "sent";
  public static final String RETRY = "retry";
  public static final String FAILED = "failed";
}
