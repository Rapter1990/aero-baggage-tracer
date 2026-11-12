package com.aerotrace.identity.keys;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Signing key settings.
 *
 * @param algorithm RS256 or ES256, used for newly generated keys
 * @param encryptionKey base64 of the 32-byte key that encrypts private keys at rest; no default
 * @param rotationInterval how long a key signs before a new one is introduced
 * @param publishLead how long a new key is published before it starts signing, so resource servers
 *     have cached the key set by the time the first token with the new key id arrives
 * @param cacheTtl how long this instance reuses the keys it loaded from the database
 * @param maxStale how long the last loaded keys may keep being used when the database cannot be
 *     reached to refresh them
 * @param maintenanceInterval how often rotation and retirement are checked
 */
@ConfigurationProperties("aerotrace.identity.keys")
public record KeyProperties(
    @DefaultValue("RS256") String algorithm,
    String encryptionKey,
    @DefaultValue("30d") Duration rotationInterval,
    @DefaultValue("10m") Duration publishLead,
    @DefaultValue("10s") Duration cacheTtl,
    @DefaultValue("30s") Duration maxStale,
    @DefaultValue("5m") Duration maintenanceInterval) {

  @Override
  public String toString() {
    return "KeyProperties[algorithm=" + algorithm + "]";
  }
}
