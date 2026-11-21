package com.aerotrace.notification.base;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

/** A clock that only moves when a test says so. Safe to read from several threads. */
public final class MutableClock extends Clock {

  public static final Instant T0 = Instant.parse("2026-03-01T10:00:00Z");

  private final AtomicReference<Instant> now = new AtomicReference<>(T0);

  public void set(Instant instant) {
    now.set(instant);
  }

  public void advance(Duration by) {
    now.updateAndGet(current -> current.plus(by));
  }

  @Override
  public ZoneId getZone() {
    return ZoneOffset.UTC;
  }

  @Override
  public Clock withZone(ZoneId zone) {
    return this;
  }

  @Override
  public Instant instant() {
    return now.get();
  }
}
