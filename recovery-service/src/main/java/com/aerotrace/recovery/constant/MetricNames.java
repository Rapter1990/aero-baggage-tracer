package com.aerotrace.recovery.constant;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/** Metric names. Labels never carry bag tags, passenger references or tokens. */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class MetricNames {

  /** Time from opening a case to resolving it; exported as recovery_resolution_seconds. */
  public static final String RECOVERY_RESOLUTION = "recovery.resolution";

  /** Events waiting to be published; exported as outbox_pending_count. */
  public static final String OUTBOX_PENDING = "outbox.pending.count";

  public static final String OUTBOX_PUBLISHED = "recovery.outbox.published";
  public static final String OUTBOX_FAILED = "recovery.outbox.failed";
  public static final String OUTBOX_PARKED = "recovery.outbox.parked";

  /** One relay run that found events; also the name of its trace span. */
  public static final String OUTBOX_RELAY = "recovery.outbox.relay";
}
