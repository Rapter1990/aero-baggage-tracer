package com.aerotrace.capacity.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Domain settings.
 *
 * @param outbox how the outbox relay publishes events
 * @param hold how long a hold that no case ever confirmed may last
 * @param service how other services prove who they are when no user token exists
 * @param jwt how tokens are verified
 */
@ConfigurationProperties("aerotrace.capacity")
public record CapacityProperties(
    @DefaultValue Outbox outbox,
    @DefaultValue Hold hold,
    @DefaultValue Service service,
    @DefaultValue Jwt jwt) {

  /** Relay settings: how often it runs, rows per run and how long published rows are kept. */
  public record Outbox(
      @DefaultValue("1s") Duration relayDelay,
      @DefaultValue("50") int batchSize,
      @DefaultValue("7d") Duration retention) {}

  /**
   * Provisional holds.
   *
   * @param expiryEnabled whether the expiry job runs. A hold counts as confirmed only after the
   *     caller says so (PUT .../confirmation); until the recovery-service does, expiry must stay
   *     off or it would take the slot of a bag that is on its way
   * @param ttl how long an unconfirmed hold lives
   * @param sweepDelay how often the job looks for expired holds
   */
  public record Hold(
      @DefaultValue("false") boolean expiryEnabled,
      @DefaultValue("1h") Duration ttl,
      @DefaultValue("30s") Duration sweepDelay) {}

  /**
   * Service-to-service calls. The recovery-service sweeper releases holds with no user token, so it
   * sends this shared secret in {@code X-Service-Token}. Empty means no service caller is accepted.
   */
  public record Service(String token) {}

  /** Token verification: the identity-service public key (inline or location) and audience. */
  public record Jwt(
      @DefaultValue("aerotrace-api") String audience, String publicKey, String publicKeyLocation) {

    /** The development public key that ships with the service, used when nothing is configured. */
    public static final String DEFAULT_PUBLIC_KEY_LOCATION = "classpath:keys/public.pem";

    public Jwt {
      if ((publicKey == null || publicKey.isBlank())
          && (publicKeyLocation == null || publicKeyLocation.isBlank())) {
        publicKeyLocation = DEFAULT_PUBLIC_KEY_LOCATION;
      }
    }
  }
}
