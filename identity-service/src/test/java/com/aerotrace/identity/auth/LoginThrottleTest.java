package com.aerotrace.identity.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.aerotrace.identity.auth.LoginThrottle.Hit;
import com.aerotrace.identity.auth.LoginThrottle.Scope;
import com.aerotrace.identity.base.AbstractIntegrationTest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/** The counter behind the lockout, against a real PostgreSQL, with time passed in by the test. */
class LoginThrottleTest extends AbstractIntegrationTest {

  private static final Instant T0 = Instant.parse("2026-11-12T10:00:00Z");
  private static final Duration WINDOW = Duration.ofMinutes(15);
  private static final Duration LOCK = Duration.ofMinutes(15);
  private static final int THRESHOLD = 3;

  @Autowired private LoginThrottle throttle;
  @Autowired private JdbcClient jdbc;

  private Hit hit(Scope scope, String key, Instant now) {
    return throttle.recordHit(scope, key, now, THRESHOLD, WINDOW, LOCK);
  }

  @Test
  void locksOnTheThresholdHitAndUnlocksWhenTheLockEnds() {
    String key = "locks-" + unique();

    assertThat(hit(Scope.LOGIN, key, T0).lockedNow()).isFalse();
    assertThat(hit(Scope.LOGIN, key, T0.plusSeconds(1)).lockedNow()).isFalse();
    assertThat(throttle.lockedUntil(Scope.LOGIN, key, T0.plusSeconds(2))).isEmpty();

    Hit third = hit(Scope.LOGIN, key, T0.plusSeconds(2));

    assertThat(third.lockedNow()).isTrue();
    assertThat(third.lockedUntil()).isEqualTo(T0.plusSeconds(2).plus(LOCK));
    assertThat(throttle.lockedUntil(Scope.LOGIN, key, T0.plusSeconds(3)))
        .contains(T0.plusSeconds(2).plus(LOCK));
    assertThat(throttle.lockedUntil(Scope.LOGIN, key, T0.plusSeconds(2).plus(LOCK))).isEmpty();
  }

  @Test
  void aHitWhileLockedDoesNotExtendTheLock() {
    String key = "no-extend-" + unique();
    hit(Scope.LOGIN, key, T0);
    hit(Scope.LOGIN, key, T0);
    Instant lockedUntil = hit(Scope.LOGIN, key, T0).lockedUntil();

    Hit during = hit(Scope.LOGIN, key, T0.plus(Duration.ofMinutes(5)));

    assertThat(during.lockedNow()).isFalse();
    assertThat(during.lockedUntil()).isEqualTo(lockedUntil);
    assertThat(throttle.lockedUntil(Scope.LOGIN, key, T0.plus(Duration.ofMinutes(5))))
        .contains(lockedUntil);
  }

  @Test
  void afterTheLockEndsTheFullAllowanceIsBack() {
    String key = "allowance-" + unique();
    hit(Scope.LOGIN, key, T0);
    hit(Scope.LOGIN, key, T0);
    hit(Scope.LOGIN, key, T0);
    Instant later = T0.plus(LOCK).plusSeconds(1);

    assertThat(hit(Scope.LOGIN, key, later).lockedNow()).isFalse();
    assertThat(hit(Scope.LOGIN, key, later).lockedNow()).isFalse();
    assertThat(hit(Scope.LOGIN, key, later).lockedNow()).isTrue();
  }

  @Test
  void hitsOutsideTheWindowDoNotAddUp() {
    String key = "window-" + unique();
    hit(Scope.LOGIN, key, T0);
    hit(Scope.LOGIN, key, T0);

    Instant afterWindow = T0.plus(WINDOW).plusSeconds(1);

    assertThat(hit(Scope.LOGIN, key, afterWindow).lockedNow()).isFalse();
    assertThat(hit(Scope.LOGIN, key, afterWindow).lockedNow()).isFalse();
  }

  @Test
  void clearingForgetsThePreviousHits() {
    String key = "clear-" + unique();
    hit(Scope.LOGIN, key, T0);
    hit(Scope.LOGIN, key, T0);

    throttle.clear(Scope.LOGIN, key);

    assertThat(hit(Scope.LOGIN, key, T0).lockedNow()).isFalse();
    assertThat(hit(Scope.LOGIN, key, T0).lockedNow()).isFalse();
  }

  @Test
  void scopesAndKeysAreIndependent() {
    String key = "scope-" + unique();
    hit(Scope.LOGIN, key, T0);
    hit(Scope.LOGIN, key, T0);
    hit(Scope.LOGIN, key, T0);

    assertThat(throttle.lockedUntil(Scope.LOGIN, key, T0)).isPresent();
    assertThat(throttle.lockedUntil(Scope.PASSWORD_RESET, key, T0)).isEmpty();
    assertThat(throttle.lockedUntil(Scope.LOGIN, "other-" + unique(), T0)).isEmpty();
  }

  @Test
  void neverStoresTheTypedTextInClear() {
    String key = "secret-" + unique() + "@example.com";

    hit(Scope.LOGIN, key, T0);

    List<String> stored =
        jdbc.sql("select key_hash from identity.login_throttle").query(String.class).list();
    assertThat(stored).isNotEmpty().allSatisfy(hash -> assertThat(hash).matches("[0-9a-f]{64}"));
    assertThat(String.join(",", stored)).doesNotContain(key).doesNotContain("secret");
  }

  @Test
  void concurrentHitsAreAllCounted() throws Exception {
    String key = "race-" + unique();
    int callers = 12;
    ExecutorService pool = Executors.newFixedThreadPool(callers);
    CountDownLatch start = new CountDownLatch(1);
    List<Future<Hit>> results = new ArrayList<>();
    for (int i = 0; i < callers; i++) {
      results.add(
          pool.submit(
              () -> {
                start.await();
                return throttle.recordHit(Scope.LOGIN, key, T0, 1_000, WINDOW, LOCK);
              }));
    }
    start.countDown();
    for (Future<Hit> result : results) {
      result.get();
    }
    pool.shutdown();

    Integer hits =
        jdbc.sql("select hits from identity.login_throttle where key_hash = :h")
            .param("h", LoginThrottle.keyHash(Scope.LOGIN, key))
            .query(Integer.class)
            .single();
    assertThat(hits).isEqualTo(callers);
  }

  @Test
  void exactlyOneOfManyConcurrentHitsTriggersTheLock() throws Exception {
    String key = "one-lock-" + unique();
    int callers = 12;
    ExecutorService pool = Executors.newFixedThreadPool(callers);
    CountDownLatch start = new CountDownLatch(1);
    List<Future<Hit>> results = new ArrayList<>();
    for (int i = 0; i < callers; i++) {
      results.add(
          pool.submit(
              () -> {
                start.await();
                return throttle.recordHit(Scope.LOGIN, key, T0, 5, WINDOW, LOCK);
              }));
    }
    start.countDown();
    long triggered = 0;
    for (Future<Hit> result : results) {
      if (result.get().lockedNow()) {
        triggered++;
      }
    }
    pool.shutdown();

    // Hits that arrive while the lock runs are not counted, so only one hit starts it.
    assertThat(triggered).isEqualTo(1);
    assertThat(throttle.lockedUntil(Scope.LOGIN, key, T0)).isPresent();
  }

  @Test
  void purgeRemovesOnlyOldRowsThatAreNotLocked() {
    String stale = "stale-" + unique();
    String locked = "locked-" + unique();
    String fresh = "fresh-" + unique();
    hit(Scope.LOGIN, stale, T0);
    hit(Scope.LOGIN, locked, T0);
    hit(Scope.LOGIN, locked, T0);
    hit(Scope.LOGIN, locked, T0);
    Instant now = T0.plus(Duration.ofHours(2));
    hit(Scope.LOGIN, fresh, now);

    int removed = throttle.purge(now.minus(Duration.ofHours(1)), now);

    assertThat(removed).isGreaterThanOrEqualTo(1);
    assertThat(exists(stale)).isFalse();
    assertThat(exists(fresh)).isTrue();
    // The lock of 15 minutes is long over by now, so that row is old and unlocked too.
    assertThat(exists(locked)).isFalse();
  }

  @Test
  void purgeKeepsARowWhoseLockIsStillRunning() {
    String locked = "still-locked-" + unique();
    hit(Scope.LOGIN, locked, T0);
    hit(Scope.LOGIN, locked, T0);
    hit(Scope.LOGIN, locked, T0);

    throttle.purge(T0.plus(Duration.ofHours(1)), T0.plusSeconds(1));

    assertThat(exists(locked)).isTrue();
  }

  private boolean exists(String key) {
    return jdbc.sql("select count(*) from identity.login_throttle where key_hash = :h")
            .param("h", LoginThrottle.keyHash(Scope.LOGIN, key))
            .query(Integer.class)
            .single()
        > 0;
  }
}
