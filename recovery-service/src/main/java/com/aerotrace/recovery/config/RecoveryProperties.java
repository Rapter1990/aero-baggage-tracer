package com.aerotrace.recovery.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Domain settings.
 *
 * @param outbox how the outbox relay publishes events
 * @param jwt how tokens are verified
 */
@ConfigurationProperties("aerotrace.recovery")
public record RecoveryProperties(@DefaultValue Outbox outbox, @DefaultValue Jwt jwt) {

  /** Relay settings: how often it runs, rows per run and how long published rows are kept. */
  public record Outbox(
      @DefaultValue("1s") Duration relayDelay,
      @DefaultValue("50") int batchSize,
      @DefaultValue("7d") Duration retention) {}

  /** Token verification: the identity-service public key (inline or location) and audience. */
  public record Jwt(
      @DefaultValue("aerotrace-api") String audience, String publicKey, String publicKeyLocation) {

    /** The development public key that ships with the service, used when nothing is configured. */
    public static final String DEFAULT_PUBLIC_KEY_LOCATION = "classpath:keys/public.pem";

    public Jwt {
      // An empty setting (an unset environment variable in a compose file) counts as not set.
      if ((publicKey == null || publicKey.isBlank())
          && (publicKeyLocation == null || publicKeyLocation.isBlank())) {
        publicKeyLocation = DEFAULT_PUBLIC_KEY_LOCATION;
      }
    }
  }
}
