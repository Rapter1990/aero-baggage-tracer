package com.aerotrace.identity.config;

import com.aerotrace.identity.keys.KeyProperties;
import com.aerotrace.identity.seed.SeedProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Refuses to start with the development values that are published in the repository: the
 * key-encryption key and the test-user password of the development compose file. Anyone can read
 * those, so a deployment that kept them would let anyone with a database dump decrypt the signing
 * keys, or sign in as the seeded admin. A development run says so with {@code
 * aerotrace.identity.allow-dev-defaults=true}.
 */
@Component
class DevDefaultsGuard {

  /** The values docker-compose.dev.yml uses; also in the README and this repository. */
  private static final String PUBLISHED_KEY_ENCRYPTION_KEY =
      "ZGV2LW9ubHktaWRlbnRpdHkta2V5LWVuY3J5cHQtMDE=";

  private static final String PUBLISHED_SEED_PASSWORD = "aerotrace-dev-password";

  DevDefaultsGuard(
      KeyProperties keys,
      SeedProperties seed,
      @Value("${aerotrace.identity.allow-dev-defaults:false}") boolean allowDevDefaults) {
    if (allowDevDefaults) {
      return;
    }
    if (PUBLISHED_KEY_ENCRYPTION_KEY.equals(keys.encryptionKey())) {
      throw new IllegalStateException(
          "The published development key-encryption key is in use. Set IDENTITY_KEY_ENCRYPTION_KEY"
              + " to your own value (openssl rand -base64 32), or set"
              + " IDENTITY_ALLOW_DEV_DEFAULTS=true for a throwaway development run.");
    }
    if (seed.enabled() && PUBLISHED_SEED_PASSWORD.equals(seed.password())) {
      throw new IllegalStateException(
          "The published development seed password is in use with seeding switched on. Set"
              + " IDENTITY_SEED_PASSWORD to your own value, switch seeding off, or set"
              + " IDENTITY_ALLOW_DEV_DEFAULTS=true for a throwaway development run.");
    }
  }
}
