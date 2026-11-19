package com.aerotrace.capacity.constant;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/** Metric names. Labels never carry bag tags, case ids or tokens. */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class MetricNames {

  /** Requests refused because the capacity was used up; exported as ..._conflicts_total. */
  public static final String RESERVATION_CONFLICTS = "capacity.reservation.conflicts";

  /** Requests for a leg that has no resource: a set-up problem, not capacity running out. */
  public static final String RESERVATION_NOT_PROVISIONED = "capacity.reservation.not.provisioned";

  public static final String RESERVATIONS_CREATED = "capacity.reservations.created";
  public static final String RESERVATIONS_RELEASED = "capacity.reservations.released";
  public static final String RESERVATIONS_EXPIRED = "capacity.reservations.expired";

  /** Events waiting to be published; exported as outbox_pending_count. */
  public static final String OUTBOX_PENDING = "outbox.pending.count";

  public static final String OUTBOX_PUBLISHED = "capacity.outbox.published";
  public static final String OUTBOX_FAILED = "capacity.outbox.failed";
  public static final String OUTBOX_PARKED = "capacity.outbox.parked";
}
