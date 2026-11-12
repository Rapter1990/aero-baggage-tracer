package com.aerotrace.identity.config;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.locks.ReentrantLock;
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
 *
 * <p>Only one caller at a time asks the database; the others carry on with the offset they already
 * have, so a slow database never makes every caller of {@link #instant()} wait. A difference larger
 * than {@code maxOffset} is not believed: a database that claims to be hours away would otherwise
 * silently stretch every lifetime, so the last sensible offset keeps being used and an error is
 * logged.
 */
public final class DatabaseClock extends Clock {

  private static final Logger log = LoggerFactory.getLogger(DatabaseClock.class);

  private record Offset(Duration value, Instant measuredAt) {}

  private final Supplier<Instant> databaseNow;
  private final Clock local;
  private final Duration refreshEvery;
  private final Duration maxOffset;
  private final ReentrantLock measuring = new ReentrantLock();

  private volatile Offset offset;

  public DatabaseClock(Supplier<Instant> databaseNow, Clock local, Duration refreshEvery) {
    this(databaseNow, local, refreshEvery, Duration.ofMinutes(10));
  }

  public DatabaseClock(
      Supplier<Instant> databaseNow, Clock local, Duration refreshEvery, Duration maxOffset) {
    this.databaseNow = databaseNow;
    this.local = local;
    this.refreshEvery = refreshEvery;
    this.maxOffset = maxOffset;
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

  private Offset measure(Instant now, Offset previous) {
    if (!measuring.tryLock()) {
      // Someone else is asking the database right now: do not queue behind them.
      return previous != null ? previous : new Offset(Duration.ZERO, now);
    }
    try {
      Offset latest = offset;
      if (latest != null && latest != previous) {
        return latest;
      }
      Duration value = previous == null ? Duration.ZERO : previous.value();
      try {
        Duration measured = Duration.between(now, databaseNow.get());
        if (measured.abs().compareTo(maxOffset) > 0) {
          log.error(
              "The database clock differs from this machine's by {} seconds, more than the {} "
                  + "seconds that is believed; ignoring it. Check the time on the database host.",
              measured.toSeconds(),
              maxOffset.toSeconds());
        } else {
          value = measured;
        }
      } catch (RuntimeException e) {
        log.warn(
            "Could not read the database time; keeping the last known offset ({})",
            e.getClass().getSimpleName());
      }
      Offset updated = new Offset(value, now);
      offset = updated;
      return updated;
    } finally {
      measuring.unlock();
    }
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
