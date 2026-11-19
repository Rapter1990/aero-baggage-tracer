package com.aerotrace.search.report;

import java.time.Instant;
import java.util.List;

/**
 * The hourly report of one airport over {@code [from, to)}, in UTC (ADR 0004, decision 7).
 * Eventually consistent: everything published before {@code indexedThrough} is counted, and the
 * indexes were {@code lagSeconds} behind when the report was made.
 *
 * @param airport the IATA code
 * @param from first hour, inclusive
 * @param to end, exclusive
 * @param indexedThrough the watermark; null when the lag could not be measured
 * @param lagSeconds 0 when caught up; null when unknown
 * @param buckets one per hour, also the hours without data (zeros)
 * @param totals the whole range
 */
public record AirportReport(
    String airport,
    Instant from,
    Instant to,
    Instant indexedThrough,
    Double lagSeconds,
    List<Bucket> buckets,
    Totals totals) {

  /**
   * One hour.
   *
   * @param hour the start of the hour (UTC)
   * @param riskyBags distinct bags whose leg from this airport turned or stayed HIGH in this hour
   * @param resolvedRecoveries cases RESOLVED in this hour (CANCELLED and CLOSED_AS_UNRECOVERABLE
   *     are not counted)
   * @param retries cases opened in this hour for a bag and leg whose earlier case ended CANCELLED
   *     or CLOSED_AS_UNRECOVERABLE
   * @param meanResolutionSeconds mean of resolvedAt - openedAt over the cases resolved in this
   *     hour; null when none was
   */
  public record Bucket(
      Instant hour,
      long riskyBags,
      long resolvedRecoveries,
      long retries,
      Double meanResolutionSeconds) {}

  /**
   * The range: distinct risky bags over all hours, summed counts, and the mean weighted by the
   * number of resolved cases (null when none).
   */
  public record Totals(
      long riskyBags, long resolvedRecoveries, long retries, Double meanResolutionSeconds) {}
}
