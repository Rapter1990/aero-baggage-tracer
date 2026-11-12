package com.aerotrace.identity.keys;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aerotrace.identity.token.TokenProperties;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * Rotation is only safe when its timings fit together. A bad combination would silently bring back
 * "rotation invalidates tokens that have not expired", so it must stop the service from starting.
 */
class KeyTimingValidationTest {

  private static final TokenProperties TOKENS =
      new TokenProperties(
          "http://identity.test", "aerotrace-api", Duration.ofMinutes(15), Duration.ofMinutes(1));

  private static KeyProperties keys(
      Duration rotationInterval,
      Duration publishLead,
      Duration cacheTtl,
      Duration maxStale,
      Duration maintenanceInterval) {
    return new KeyProperties(
        "RS256", "unused", rotationInterval, publishLead, cacheTtl, maxStale, maintenanceInterval);
  }

  private static KeyProperties defaults() {
    return keys(
        Duration.ofDays(30),
        Duration.ofMinutes(10),
        Duration.ofSeconds(10),
        Duration.ofMinutes(5),
        Duration.ofMinutes(5));
  }

  @Test
  void theShippedDefaultsAreAccepted() {
    assertThatCode(() -> SigningKeyService.validateTimings(defaults(), TOKENS))
        .doesNotThrowAnyException();
  }

  @Test
  void aCacheAsLongAsTheClockSkewIsRefused() {
    KeyProperties bad =
        keys(
            Duration.ofDays(30),
            Duration.ofMinutes(10),
            Duration.ofMinutes(1),
            Duration.ofMinutes(5),
            Duration.ofMinutes(5));

    assertThatThrownBy(() -> SigningKeyService.validateTimings(bad, TOKENS))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("cache-ttl")
        .hasMessageContaining("clock-skew");
  }

  @Test
  void aPublishLeadShorterThanTheCacheIsRefused() {
    KeyProperties bad =
        keys(
            Duration.ofDays(30),
            Duration.ofSeconds(5),
            Duration.ofSeconds(10),
            Duration.ofMinutes(5),
            Duration.ofMinutes(5));

    assertThatThrownBy(() -> SigningKeyService.validateTimings(bad, TOKENS))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("publish-lead");
  }

  @Test
  void rotatingFasterThanKeysRetireIsRefused() {
    // 10 min lead + 15 min ttl + 1 min skew = 26 min needed.
    KeyProperties bad =
        keys(
            Duration.ofMinutes(20),
            Duration.ofMinutes(10),
            Duration.ofSeconds(10),
            Duration.ofMinutes(5),
            Duration.ofMinutes(5));

    assertThatThrownBy(() -> SigningKeyService.validateTimings(bad, TOKENS))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("rotation-interval");
  }

  @Test
  void aMaintenanceIntervalOfZeroIsRefused() {
    KeyProperties bad =
        keys(
            Duration.ofDays(30),
            Duration.ofMinutes(10),
            Duration.ofSeconds(10),
            Duration.ofMinutes(5),
            Duration.ZERO);

    assertThatThrownBy(() -> SigningKeyService.validateTimings(bad, TOKENS))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("maintenance-interval");
  }

  @Test
  void aStaleAllowanceShorterThanTheCacheIsRefused() {
    KeyProperties bad =
        keys(
            Duration.ofDays(30),
            Duration.ofMinutes(10),
            Duration.ofSeconds(10),
            Duration.ofSeconds(5),
            Duration.ofMinutes(5));

    assertThatThrownBy(() -> SigningKeyService.validateTimings(bad, TOKENS))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("max-stale");
  }

  @Test
  void aTokenThatNeverExpiresIsRefused() {
    TokenProperties bad =
        new TokenProperties(
            "http://identity.test", "aerotrace-api", Duration.ZERO, Duration.ofMinutes(1));

    assertThatThrownBy(() -> SigningKeyService.validateTimings(defaults(), bad))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("token.ttl");
  }

  @Test
  void testsMayUseNoCacheAndNoLead() {
    KeyProperties relaxed =
        keys(
            Duration.ofDays(30),
            Duration.ZERO,
            Duration.ZERO,
            Duration.ofMinutes(5),
            Duration.ofMinutes(5));

    assertThat(relaxed.cacheTtl()).isZero();
    assertThatCode(() -> SigningKeyService.validateTimings(relaxed, TOKENS))
        .doesNotThrowAnyException();
  }
}
