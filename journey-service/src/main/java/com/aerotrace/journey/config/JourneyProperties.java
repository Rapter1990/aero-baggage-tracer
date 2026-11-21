package com.aerotrace.journey.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Domain settings.
 *
 * @param minimumConnectionTime shortest time between landing and the next departure
 * @param lateEventGrace how old a scan may be on arrival before it is flagged LATE_EVENT (BR-06)
 * @param maxClockSkew how far in the future a scan's timestamp may be
 * @param export who may read the internal bag export
 */
@ConfigurationProperties("aerotrace.journey")
public record JourneyProperties(
    @DefaultValue("45m") Duration minimumConnectionTime,
    @DefaultValue("10m") Duration lateEventGrace,
    @DefaultValue("5m") Duration maxClockSkew,
    @DefaultValue Outbox outbox,
    @DefaultValue Export export,
    @DefaultValue Jwt jwt) {

  /**
   * The internal bag export ({@code GET /internal/v1/export/bags}) the search-service rebuilds from
   * (ADR 0004).
   *
   * @param serviceToken the shared secret the caller sends in {@code X-Service-Token}; at least 16
   *     characters. Empty (or shorter) refuses every call, except in the {@code local} and {@code
   *     dev} profiles, where the export is open for local runs
   */
  public record Export(@DefaultValue("") String serviceToken) {}

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
