package com.aerotrace.identity.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.aerotrace.identity.support.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * Replicas must agree on when a signing key activates or retires, so time comes from the database
 * rather than each machine's own, possibly wrong, clock.
 */
class DatabaseClockTest {

  private static final Instant LOCAL_START = Instant.parse("2030-01-01T00:00:00Z");

  private final MutableClock local = new MutableClock(LOCAL_START);

  @Test
  void followsTheDatabaseWhenTheLocalClockIsAhead() {
    DatabaseClock clock =
        new DatabaseClock(
            () -> LOCAL_START.minus(Duration.ofMinutes(7)), local, Duration.ofMinutes(1));

    assertThat(clock.instant()).isEqualTo(LOCAL_START.minus(Duration.ofMinutes(7)));
  }

  @Test
  void followsTheDatabaseWhenTheLocalClockIsBehind() {
    DatabaseClock clock =
        new DatabaseClock(
            () -> LOCAL_START.plus(Duration.ofMinutes(3)), local, Duration.ofMinutes(1));

    assertThat(clock.instant()).isEqualTo(LOCAL_START.plus(Duration.ofMinutes(3)));
  }

  @Test
  void keepsTimeMovingBetweenMeasurements() {
    DatabaseClock clock = new DatabaseClock(() -> LOCAL_START, local, Duration.ofMinutes(1));
    clock.instant();

    local.advance(Duration.ofSeconds(30));

    assertThat(clock.instant()).isEqualTo(LOCAL_START.plusSeconds(30));
  }

  @Test
  void asksTheDatabaseAgainOnlyAfterTheRefreshInterval() {
    AtomicInteger asked = new AtomicInteger();
    DatabaseClock clock =
        new DatabaseClock(
            () -> {
              asked.incrementAndGet();
              return local.instant();
            },
            local,
            Duration.ofMinutes(1));

    clock.instant();
    local.advance(Duration.ofSeconds(59));
    clock.instant();
    assertThat(asked).hasValue(1);

    local.advance(Duration.ofSeconds(2));
    clock.instant();
    assertThat(asked).hasValue(2);
  }

  @Test
  void picksUpAChangedOffsetOnTheNextMeasurement() {
    AtomicReference<Duration> drift = new AtomicReference<>(Duration.ZERO);
    DatabaseClock clock =
        new DatabaseClock(() -> local.instant().plus(drift.get()), local, Duration.ofMinutes(1));
    clock.instant();

    drift.set(Duration.ofMinutes(5));
    local.advance(Duration.ofMinutes(2));

    assertThat(clock.instant()).isEqualTo(local.instant().plus(Duration.ofMinutes(5)));
  }

  @Test
  void keepsTheLastOffsetWhenTheDatabaseCannotBeAsked() {
    AtomicInteger calls = new AtomicInteger();
    DatabaseClock clock =
        new DatabaseClock(
            () -> {
              if (calls.incrementAndGet() > 1) {
                throw new IllegalStateException("database down");
              }
              return LOCAL_START.plus(Duration.ofMinutes(4));
            },
            local,
            Duration.ofMinutes(1));
    clock.instant();

    local.advance(Duration.ofMinutes(2));

    assertThat(clock.instant()).isEqualTo(local.instant().plus(Duration.ofMinutes(4)));
  }

  @Test
  void usesTheLocalClockUntilTheDatabaseHasEverAnswered() {
    DatabaseClock clock =
        new DatabaseClock(
            () -> {
              throw new IllegalStateException("database down");
            },
            local,
            Duration.ofMinutes(1));

    assertThat(clock.instant()).isEqualTo(LOCAL_START);
  }

  @Test
  void isAlwaysUtc() {
    DatabaseClock clock = new DatabaseClock(() -> LOCAL_START, local, Duration.ofMinutes(1));

    assertThat(clock.getZone().getId()).isEqualTo("Z");
    assertThat(clock.withZone(java.time.ZoneId.of("Asia/Istanbul"))).isSameAs(clock);
  }

  @Test
  void anAbsurdDatabaseTimeIsIgnoredNotFollowed() {
    DatabaseClock clock =
        new DatabaseClock(
            () -> LOCAL_START.plus(Duration.ofHours(3)),
            local,
            Duration.ofMinutes(1),
            Duration.ofMinutes(10));

    // A database that claims it is hours away would silently stretch every token and key lifetime.
    assertThat(clock.instant()).isEqualTo(LOCAL_START);
  }

  @Test
  void aDifferenceWithinTheBoundIsFollowed() {
    DatabaseClock clock =
        new DatabaseClock(
            () -> LOCAL_START.plus(Duration.ofMinutes(9)),
            local,
            Duration.ofMinutes(1),
            Duration.ofMinutes(10));

    assertThat(clock.instant()).isEqualTo(LOCAL_START.plus(Duration.ofMinutes(9)));
  }

  @Test
  void aSlowDatabaseDoesNotMakeOtherCallersWait() throws Exception {
    CountDownLatch asking = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    DatabaseClock clock =
        new DatabaseClock(
            () -> {
              asking.countDown();
              try {
                release.await();
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              }
              return LOCAL_START;
            },
            local,
            Duration.ofMinutes(1));
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      Future<Instant> measuring = pool.submit(clock::instant);
      asking.await();

      Future<Instant> other = pool.submit(clock::instant);

      // Answered at once from the local clock while the first caller waits for the database.
      assertThat(other.get(2, TimeUnit.SECONDS)).isEqualTo(LOCAL_START);
      release.countDown();
      assertThat(measuring.get()).isEqualTo(LOCAL_START);
    } finally {
      pool.shutdownNow();
    }
  }
}
