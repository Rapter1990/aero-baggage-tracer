package com.aerotrace.notification.delivery;

import java.time.Duration;
import java.util.function.DoubleSupplier;

/**
 * The wait before the next delivery attempt: {@code min(base * 2^(failures - 1), max)}, moved at
 * random by up to {@code jitter} of itself either way and never past {@code max} (ADR 0005,
 * decision 9). With the defaults (2 s base, 5 attempts) the waits are 2, 4, 8 and 16 s. The wait is
 * a duration: the repository adds it to the database's {@code now()}, so no replica's clock decides
 * when the next attempt is due.
 */
public final class BackoffPolicy {

  private final Duration base;
  private final Duration max;
  private final double jitter;
  private final DoubleSupplier random;

  /**
   * @param jitter the fraction, 0 to 1, the wait may move either way
   * @param random a source of numbers from 0 to 1
   */
  public BackoffPolicy(Duration base, Duration max, double jitter, DoubleSupplier random) {
    if (base.isNegative() || base.isZero() || max.compareTo(base) < 0) {
      throw new IllegalArgumentException("base must be positive and not above max");
    }
    if (jitter < 0 || jitter > 1) {
      throw new IllegalArgumentException("jitter must be from 0 to 1");
    }
    this.base = base;
    this.max = max;
    this.jitter = jitter;
    this.random = random;
  }

  /** The wait after {@code failures} failed attempts (1 or more). */
  public Duration delayAfter(int failures) {
    if (failures < 1) {
      throw new IllegalArgumentException("failures must be at least 1");
    }
    long maxMillis = max.toMillis();
    long baseMillis = base.toMillis();
    int doublings = failures - 1;
    // Doubling past the maximum (or past what a long holds) is the maximum.
    long raw =
        doublings >= Long.SIZE - 2 || baseMillis > (maxMillis >> doublings)
            ? maxMillis
            : Math.min(baseMillis << doublings, maxMillis);
    if (jitter == 0) {
      return Duration.ofMillis(raw);
    }
    double factor = 1 + jitter * (2 * random.getAsDouble() - 1);
    long jittered = Math.round(raw * factor);
    return Duration.ofMillis(Math.max(0, Math.min(jittered, maxMillis)));
  }
}
