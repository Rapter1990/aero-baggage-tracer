package com.aerotrace.identity.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aerotrace.identity.keys.KeyProperties;
import com.aerotrace.identity.seed.SeedProperties;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * The development stack publishes a key-encryption key and a test-user password in the repository.
 * They must never be accepted outside an explicitly marked development run.
 */
class DevDefaultsGuardTest {

  private static final String PUBLISHED_KEY = "ZGV2LW9ubHktaWRlbnRpdHkta2V5LWVuY3J5cHQtMDE=";
  private static final String PUBLISHED_PASSWORD = "aerotrace-dev-password";
  private static final String OWN_KEY = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=";

  private static KeyProperties keys(String key) {
    return new KeyProperties(
        "RS256",
        key,
        Duration.ofDays(30),
        Duration.ofMinutes(10),
        Duration.ofSeconds(10),
        Duration.ofMinutes(5),
        Duration.ofMinutes(5));
  }

  @Test
  void thePublishedKeyEncryptionKeyIsRefusedByDefault() {
    assertThatThrownBy(
            () -> new DevDefaultsGuard(keys(PUBLISHED_KEY), new SeedProperties(false, null), false))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("key-encryption key")
        .hasMessageNotContaining(PUBLISHED_KEY);
  }

  @Test
  void thePublishedSeedPasswordIsRefusedWhenSeedingIsOn() {
    assertThatThrownBy(
            () ->
                new DevDefaultsGuard(
                    keys(OWN_KEY), new SeedProperties(true, PUBLISHED_PASSWORD), false))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("seed password")
        .hasMessageNotContaining(PUBLISHED_PASSWORD);
  }

  @Test
  void thePublishedSeedPasswordIsHarmlessWhileSeedingIsOff() {
    assertThatCode(
            () ->
                new DevDefaultsGuard(
                    keys(OWN_KEY), new SeedProperties(false, PUBLISHED_PASSWORD), false))
        .doesNotThrowAnyException();
  }

  @Test
  void yourOwnValuesAreAlwaysFine() {
    assertThatCode(
            () ->
                new DevDefaultsGuard(
                    keys(OWN_KEY), new SeedProperties(true, "my-own-long-password"), false))
        .doesNotThrowAnyException();
  }

  @Test
  void aDevelopmentRunMayUseThePublishedValues() {
    assertThatCode(
            () ->
                new DevDefaultsGuard(
                    keys(PUBLISHED_KEY), new SeedProperties(true, PUBLISHED_PASSWORD), true))
        .doesNotThrowAnyException();
  }
}
