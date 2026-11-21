package com.aerotrace.notification.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings of the notification-service (ADR 0005).
 *
 * @param consumers how {@code recovery.case.changed.v1} is read
 * @param delivery how notifications are sent and retried
 * @param retention how long a finished notification is kept; longer than the topic (7 days) and
 *     dead-letter topic (30 days) retention, because purging removes the dedupe key
 * @param purge how the retention purge runs
 * @param docs whether the OpenAPI definition is served
 * @param jwt how tokens are verified
 */
@ConfigurationProperties("aerotrace.notification")
public record NotificationProperties(
    @DefaultValue Consumers consumers,
    @DefaultValue Delivery delivery,
    @DefaultValue("90d") Duration retention,
    @DefaultValue Purge purge,
    @DefaultValue Docs docs,
    @DefaultValue Jwt jwt) {

  /**
   * The case-changed consumer. A transient failure is retried with a doubling wait; the defaults (6
   * deliveries: waits of 2, 4, 8, 16 and 30 s) span about a minute, long enough to outlast a
   * database failover, before the event is dead-lettered.
   *
   * @param enabled whether the listener starts
   * @param retryAttempts deliveries of a failing event in total (the first one included)
   * @param retryBackoff the first wait between deliveries; it doubles each time
   * @param retryMaxBackoff the longest single wait
   */
  public record Consumers(
      @DefaultValue("true") boolean enabled,
      @DefaultValue("6") int retryAttempts,
      @DefaultValue("2s") Duration retryBackoff,
      @DefaultValue("30s") Duration retryMaxBackoff) {}

  /**
   * Delivery through the provider (claim, send, finish; ADR 0005 decision 9).
   *
   * @param dispatchDelay how often due notifications are looked for
   * @param maxAttempts attempts before a notification is FAILED
   * @param baseDelay the wait after the first failed attempt; it doubles after each further one
   * @param maxDelay the longest wait between two attempts
   * @param jitter the fraction (0 to 1) the wait is moved by at random, either way
   * @param batchSize notifications claimed per run
   * @param lease how long a claimed notification belongs to the replica that claimed it; must be
   *     longer than the provider timeout
   * @param providerTimeout the hard limit of one provider call
   * @param deadLetterTimeout how long the dead-letter publisher waits for Kafka to acknowledge
   */
  public record Delivery(
      @DefaultValue("1s") Duration dispatchDelay,
      @DefaultValue("5") int maxAttempts,
      @DefaultValue("2s") Duration baseDelay,
      @DefaultValue("5m") Duration maxDelay,
      @DefaultValue("0.2") double jitter,
      @DefaultValue("50") int batchSize,
      @DefaultValue("60s") Duration lease,
      @DefaultValue("10s") Duration providerTimeout,
      @DefaultValue("10s") Duration deadLetterTimeout) {

    public Delivery {
      if (maxAttempts < 1) {
        throw new IllegalArgumentException(
            "aerotrace.notification.delivery.max-attempts must be >= 1");
      }
      if (batchSize < 1) {
        throw new IllegalArgumentException(
            "aerotrace.notification.delivery.batch-size must be >= 1");
      }
      if (jitter < 0 || jitter > 1) {
        throw new IllegalArgumentException("aerotrace.notification.delivery.jitter must be 0 to 1");
      }
      if (lease.compareTo(providerTimeout) <= 0 || lease.compareTo(deadLetterTimeout) <= 0) {
        throw new IllegalArgumentException(
            "aerotrace.notification.delivery.lease must be longer than the provider and"
                + " dead-letter timeouts");
      }
    }
  }

  /**
   * The retention purge.
   *
   * @param delay how often it runs
   * @param batchSize rows deleted per statement, so one run never holds a long lock
   */
  public record Purge(@DefaultValue("1h") Duration delay, @DefaultValue("500") int batchSize) {}

  /** Whether {@code /v3/api-docs} is served. */
  public record Docs(@DefaultValue("true") boolean enabled) {}

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
