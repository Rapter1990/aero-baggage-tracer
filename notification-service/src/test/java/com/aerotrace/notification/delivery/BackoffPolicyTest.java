package com.aerotrace.notification.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Pure unit tests: a fixed clock and a fixed random source, so every number is exact. */
class BackoffPolicyTest {

  private static final Instant NOW = Instant.parse("2026-03-01T10:00:00Z");
  private static final Clock FIXED = Clock.fixed(NOW, ZoneOffset.UTC);
  private static final Duration BASE = Duration.ofSeconds(2);
  private static final Duration MAX = Duration.ofMinutes(5);

  private static BackoffPolicy withoutJitter() {
    return new BackoffPolicy(BASE, MAX, 0.0, () -> 0.5);
  }

  @Test
  @DisplayName("BR-08 the wait doubles after each failed attempt: 2s, 4s, 8s, 16s, 32s")
  void waitDoubles() {
    BackoffPolicy policy = withoutJitter();

    assertThat(policy.delayAfter(1)).isEqualTo(Duration.ofSeconds(2));
    assertThat(policy.delayAfter(2)).isEqualTo(Duration.ofSeconds(4));
    assertThat(policy.delayAfter(3)).isEqualTo(Duration.ofSeconds(8));
    assertThat(policy.delayAfter(4)).isEqualTo(Duration.ofSeconds(16));
    assertThat(policy.delayAfter(5)).isEqualTo(Duration.ofSeconds(32));
  }

  @Test
  @DisplayName("BR-08 the wait grows until the maximum and never passes it")
  void waitGrowsToTheMaximum() {
    BackoffPolicy policy = withoutJitter();

    // 2s * 2^7 = 256s is the last step below five minutes; the next one is capped at 300s.
    assertThat(policy.delayAfter(8)).isEqualTo(Duration.ofSeconds(256));
    assertThat(policy.delayAfter(9)).isEqualTo(MAX);
    assertThat(policy.delayAfter(10)).isEqualTo(MAX);
    Duration previous = Duration.ZERO;
    for (int failures = 1; failures <= 40; failures++) {
      Duration delay = policy.delayAfter(failures);
      assertThat(delay).isGreaterThanOrEqualTo(previous).isLessThanOrEqualTo(MAX);
      previous = delay;
    }
  }

  @Test
  @DisplayName("BR-08 a huge number of failures does not overflow the wait")
  void hugeFailureCountStaysAtTheMaximum() {
    assertThat(withoutJitter().delayAfter(1_000)).isEqualTo(MAX);
    assertThat(withoutJitter().delayAfter(Integer.MAX_VALUE)).isEqualTo(MAX);
  }

  @Test
  @DisplayName("BR-08 the next attempt is the clock's now plus the wait")
  void nextAttemptIsNowPlusTheWait() {
    BackoffPolicy policy = withoutJitter();

    assertThat(policy.nextAttemptAt(FIXED, 1)).isEqualTo(NOW.plusSeconds(2));
    assertThat(policy.nextAttemptAt(FIXED, 3)).isEqualTo(NOW.plusSeconds(8));
    assertThat(policy.nextAttemptAt(FIXED, 12)).isEqualTo(NOW.plus(MAX));
  }

  @Test
  @DisplayName("BR-08 jitter moves the wait by at most its fraction either way")
  void jitterStaysInsideItsBounds() {
    // A fraction of 0.25 around 2s is 1.5s (random 0) to 2.5s (random 1), 2s in the middle.
    assertThat(new BackoffPolicy(BASE, MAX, 0.25, () -> 0.0).delayAfter(1))
        .isEqualTo(Duration.ofMillis(1_500));
    assertThat(new BackoffPolicy(BASE, MAX, 0.25, () -> 0.5).delayAfter(1))
        .isEqualTo(Duration.ofSeconds(2));
    assertThat(new BackoffPolicy(BASE, MAX, 0.25, () -> 1.0).delayAfter(1))
        .isEqualTo(Duration.ofMillis(2_500));
  }

  @Test
  @DisplayName("BR-08 jitter never pushes the wait past the maximum")
  void jitterNeverPassesTheMaximum() {
    BackoffPolicy policy = new BackoffPolicy(BASE, MAX, 0.5, () -> 1.0);

    assertThat(policy.delayAfter(30)).isEqualTo(MAX);
  }

  @Test
  @DisplayName("BR-08 a failure count below one is a programming error")
  void failureCountMustBePositive() {
    assertThatThrownBy(() -> withoutJitter().delayAfter(0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> withoutJitter().delayAfter(-3))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
