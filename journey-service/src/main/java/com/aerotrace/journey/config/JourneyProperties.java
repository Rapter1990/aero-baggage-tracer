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
 */
@ConfigurationProperties("aerotrace.journey")
public record JourneyProperties(
    @DefaultValue("45m") Duration minimumConnectionTime,
    @DefaultValue("10m") Duration lateEventGrace,
    @DefaultValue("5m") Duration maxClockSkew,
    @DefaultValue Outbox outbox,
    @DefaultValue Jwt jwt) {

  /** Relay settings: how often it runs, rows per run and how long published rows are kept. */
  public record Outbox(
      @DefaultValue("1s") Duration relayDelay,
      @DefaultValue("50") int batchSize,
      @DefaultValue("7d") Duration retention) {}

  /** Token verification: the identity-service public key (inline or location) and audience. */
  public record Jwt(
      @DefaultValue("aerotrace-api") String audience,
      String publicKey,
      @DefaultValue("classpath:keys/public.pem") String publicKeyLocation) {}
}
