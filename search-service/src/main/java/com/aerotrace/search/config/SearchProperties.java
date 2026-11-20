package com.aerotrace.search.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings of the search-service.
 *
 * @param consumers the Kafka indexer
 * @param index index lifecycle (point-in-time keep-alive, rebuild timing)
 * @param cursor the signing of search cursors
 * @param reindex the rebuild job
 * @param export the bulk-export endpoints of the owning services (the rebuild source)
 * @param lag how often the indexing lag is measured
 * @param report report limits
 * @param jwt how tokens are verified
 */
@ConfigurationProperties("aerotrace.search")
public record SearchProperties(
    @DefaultValue Consumers consumers,
    @DefaultValue Index index,
    @DefaultValue Cursor cursor,
    @DefaultValue Reindex reindex,
    @DefaultValue Export export,
    @DefaultValue Lag lag,
    @DefaultValue Report report,
    @DefaultValue Jwt jwt) {

  /**
   * Kafka consumers.
   *
   * @param enabled whether the listeners start
   * @param groupId the consumer group (also the group whose lag is measured)
   * @param retryBackoff first wait before a failed batch is tried again; it doubles each time
   * @param maxRetryBackoff the longest wait between two tries. A batch that fails because
   *     Elasticsearch is down is retried until it is back: it is never dead-lettered
   */
  public record Consumers(
      @DefaultValue("true") boolean enabled,
      @DefaultValue("search-service") String groupId,
      @DefaultValue("200ms") Duration retryBackoff,
      @DefaultValue("30s") Duration maxRetryBackoff) {}

  /**
   * Index lifecycle.
   *
   * @param replicas replicas of every index (0 on a single node)
   * @param pitKeepAlive how long a point-in-time of a search page stays open
   * @param liveSettle how long a rebuild waits after adding the new index to the live alias before
   *     it reads the source, so that every write already in flight reaches both indexes
   * @param oldIndexGrace how long an index that left both aliases is kept (longer than the
   *     point-in-time keep-alive, so an open cursor never loses its index)
   * @param janitorDelay how often unused indexes are looked for
   */
  public record Index(
      @DefaultValue("0") int replicas,
      @DefaultValue("2m") Duration pitKeepAlive,
      @DefaultValue("5s") Duration liveSettle,
      @DefaultValue("5m") Duration oldIndexGrace,
      @DefaultValue("1m") Duration janitorDelay) {}

  /**
   * Cursor signing.
   *
   * @param secret the HMAC key of the search cursors, shared by every instance. Empty: a random key
   *     per instance (cursors then only work on the instance that issued them)
   */
  public record Cursor(@DefaultValue("") String secret) {}

  /**
   * The rebuild job.
   *
   * @param pageSize how many records one export page asks for
   * @param lockLease how long the rebuild lock is held without a heartbeat before another instance
   *     may take it over
   * @param heartbeat how often a running job renews its lock; a job that cannot renew it, or finds
   *     it taken over, stops and fails
   * @param maxPages the most export pages one family may take; a source that pages on and on fails
   *     the job instead of looping
   * @param maxDuration how long a job may run in all before it fails
   * @param keyTtl how long an Idempotency-Key names its job; an older key may be used again
   */
  public record Reindex(
      @DefaultValue("500") int pageSize,
      @DefaultValue("2m") Duration lockLease,
      @DefaultValue("30s") Duration heartbeat,
      @DefaultValue("100000") int maxPages,
      @DefaultValue("2h") Duration maxDuration,
      @DefaultValue("24h") Duration keyTtl) {}

  /**
   * The internal export endpoints of the owning services (#28, #29).
   *
   * @param journeyUrl where the journey-service answers
   * @param recoveryUrl where the recovery-service answers
   * @param serviceToken the shared secret sent as {@code X-Service-Token}; empty sends none
   * @param connectTimeout how long to wait for a connection
   * @param readTimeout how long to wait for one page
   * @param maxPageBytes the largest answer one export page may have; a larger one fails the job
   */
  public record Export(
      @DefaultValue("http://localhost:1113") String journeyUrl,
      @DefaultValue("http://localhost:1115") String recoveryUrl,
      @DefaultValue("") String serviceToken,
      @DefaultValue("2s") Duration connectTimeout,
      @DefaultValue("30s") Duration readTimeout,
      @DefaultValue("16777216") int maxPageBytes) {}

  /**
   * Indexing lag.
   *
   * @param checkInterval how often the consumer-group lag is read from Kafka
   */
  public record Lag(@DefaultValue("15s") Duration checkInterval) {}

  /**
   * Reports.
   *
   * @param maxRange the longest {@code [from, to)} a report may cover
   */
  public record Report(@DefaultValue("31d") Duration maxRange) {}

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
