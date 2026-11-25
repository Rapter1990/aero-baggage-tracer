package com.aerotrace.journey.constant;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/** Metric names. Labels never carry bag tags, passenger references or tokens. */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class MetricNames {

  public static final String SCANS_INGESTED = "journey.scans.ingested";
  public static final String OUTBOX_PUBLISHED = "journey.outbox.published";
  public static final String OUTBOX_FAILED = "journey.outbox.failed";
  public static final String OUTBOX_PARKED = "journey.outbox.parked";

  /** Events waiting to be published (neither published nor parked). */
  public static final String OUTBOX_PENDING = "journey.outbox.pending.events";

  /** Seconds the oldest waiting event has been waiting; 0 when nothing waits. */
  public static final String OUTBOX_OLDEST_PENDING_AGE = "journey.outbox.oldest.pending.age";

  /** Time from storing an event to the broker acknowledging it. */
  public static final String OUTBOX_PUBLISH_DELAY = "journey.outbox.publish.delay";

  /** One relay run that found events; also the name of its trace span. */
  public static final String OUTBOX_RELAY = "journey.outbox.relay";

  /** 1 while the contract step of the scan_source migration has not been applied, else 0. */
  public static final String MIGRATION_CONTRACT_PENDING = "journey.migration.contract.pending";
}
