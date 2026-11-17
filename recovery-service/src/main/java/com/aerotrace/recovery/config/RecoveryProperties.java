package com.aerotrace.recovery.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Domain settings.
 *
 * @param outbox how the outbox relay publishes events
 * @param capacity how the capacity-service is called and how long a hold may last
 * @param jwt how tokens are verified
 */
@ConfigurationProperties("aerotrace.recovery")
public record RecoveryProperties(
    @DefaultValue Outbox outbox,
    @DefaultValue Capacity capacity,
    @DefaultValue Strategy strategy,
    @DefaultValue Consumers consumers,
    @DefaultValue Jwt jwt) {

  /**
   * Recovery action choice.
   *
   * @param minimumExpediteTime the least time a transfer needs; with less left the bag is rerouted
   */
  public record Strategy(@DefaultValue("5m") Duration minimumExpediteTime) {}

  /**
   * Kafka consumers.
   *
   * @param enabled whether the listeners start
   * @param retryAttempts how often a failing event is retried before it is dead-lettered
   * @param retryBackoff the first wait between retries; it doubles each time
   * @param dedupeRetention how long a processed event id is remembered; keep it longer than the
   *     retention of the topics, or a redelivered event could be applied twice
   */
  public record Consumers(
      @DefaultValue("true") boolean enabled,
      @DefaultValue("3") int retryAttempts,
      @DefaultValue("200ms") Duration retryBackoff,
      @DefaultValue("14d") Duration dedupeRetention) {}

  /**
   * The capacity-service client.
   *
   * @param baseUrl where the capacity-service answers (a Compose or Kubernetes service name)
   * @param connectTimeout how long to wait for a connection
   * @param readTimeout how long to wait for an answer; a slower call counts as unavailable
   * @param holdTimeout how long a case may stay RESERVED before its hold is released and the case
   *     cancelled
   * @param sweepDelay how often expired holds and unreleased holds are looked for
   */
  public record Capacity(
      @DefaultValue("http://localhost:1116") String baseUrl,
      @DefaultValue("2s") Duration connectTimeout,
      @DefaultValue("5s") Duration readTimeout,
      @DefaultValue("15m") Duration holdTimeout,
      @DefaultValue("30s") Duration sweepDelay) {}

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
