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
}
