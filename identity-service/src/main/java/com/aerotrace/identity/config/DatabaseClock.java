package com.aerotrace.identity.config;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A clock that tells the time of the database, so every replica agrees on it.
 *
 * <p>Signing keys activate and retire at moments stored in the database. If each replica judged
 * those moments by its own clock, one with a wrong clock could erase a key whose tokens are still
 * valid. This clock measures the difference to the database now and then and applies it to the
 * local clock in between, so it stays cheap and keeps ticking if the database is briefly away.
 */
public final class DatabaseClock extends Clock {

  private static final Logger log = LoggerFactory.getLogger(DatabaseClock.class);

  private record Offset(Duration value, Instant measuredAt) {}

  private final Supplier<Instant> databaseNow;
  private final Clock local;
  private final Duration refreshEvery;

  private volatile Offset offset;

  public DatabaseClock(Supplier<Instant> databaseNow, Clock local, Duration refreshEvery) {
    this.databaseNow = databaseNow;
    this.local = local;
    this.refreshEvery = refreshEvery;
  }

  @Override
  public Instant instant() {
    Instant now = local.instant();
    Offset current = offset;
    if (current == null
        || now.isBefore(current.measuredAt())
        || !now.isBefore(current.measuredAt().plus(refreshEvery))) {
      current = measure(now, current);
    }
    return now.plus(current.value());
  }

  private synchronized Offset measure(Instant now, Offset previous) {
    Offset latest = offset;
    if (latest != null && latest != previous) {
      return latest;
    }
    Duration value = previous == null ? Duration.ZERO : previous.value();
    try {
      value = Duration.between(now, databaseNow.get());
    } catch (RuntimeException e) {
      log.warn(
          "Could not read the database time; keeping the last known offset ({})",
          e.getClass().getSimpleName());
    }
    Offset measured = new Offset(value, now);
    offset = measured;
    return measured;
  }

  @Override
  public ZoneId getZone() {
    return ZoneOffset.UTC;
  }

  @Override
  public Clock withZone(ZoneId zone) {
    return this;
  }
}
