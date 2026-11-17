package com.aerotrace.recovery.policy;

import com.aerotrace.recovery.config.RecoveryProperties;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * The Redis side of the policy cache. Every method is safe to call when Redis is down: a failure is
 * counted and logged by class name, and the caller is told "no answer", never an exception. That is
 * what lets the service run without Redis. Nothing here decides anything about cases or capacity.
 *
 * <p>The value is {@code minutes:version}. The key has a TTL, so even an entry that could not be
 * evicted expires on its own.
 */
@Component
public class PolicyCache {

  private static final Logger LOG = LoggerFactory.getLogger(PolicyCache.class);
  static final String KEY_PREFIX = "recovery:policy:";

  private final StringRedisTemplate redis;
  private final Duration ttl;
  private final MeterRegistry meters;

  public PolicyCache(
      StringRedisTemplate redis, RecoveryProperties properties, MeterRegistry meters) {
    this.redis = redis;
    this.ttl = properties.policy().cacheTtl();
    this.meters = meters;
  }

  static String keyOf(String policyKey) {
    return KEY_PREFIX + policyKey;
  }

  /** The cached policy, or empty on a miss, an unreadable entry or when Redis is unavailable. */
  public Optional<PolicyView> get(String policyKey) {
    try {
      String value = redis.opsForValue().get(keyOf(policyKey));
      if (value == null) {
        count("miss");
        return Optional.empty();
      }
      Optional<PolicyView> parsed = parse(value);
      if (parsed.isEmpty()) {
        // A value we did not write: forget it, the database decides.
        count("corrupt");
        evict(policyKey);
        return Optional.empty();
      }
      count("hit");
      return parsed;
    } catch (RuntimeException unavailable) {
      unavailable(unavailable);
      return Optional.empty();
    }
  }

  /** Stores the policy for the TTL; a failure only means the next read goes to the database. */
  public void put(String policyKey, PolicyView policy) {
    try {
      redis
          .opsForValue()
          .set(keyOf(policyKey), policy.minimumExpediteMinutes() + ":" + policy.version(), ttl);
    } catch (RuntimeException unavailable) {
      unavailable(unavailable);
    }
  }

  /**
   * Forgets the cached policy after it changed. When Redis cannot be reached the old entry may stay
   * until its TTL runs out: the longest a change can go unseen.
   */
  public void evict(String policyKey) {
    try {
      redis.delete(keyOf(policyKey));
    } catch (RuntimeException unavailable) {
      unavailable(unavailable);
    }
  }

  private static Optional<PolicyView> parse(String value) {
    int colon = value.indexOf(':');
    if (colon <= 0) {
      return Optional.empty();
    }
    try {
      int minutes = Integer.parseInt(value.substring(0, colon));
      long version = Long.parseLong(value.substring(colon + 1));
      return minutes < 0 || version < 1
          ? Optional.empty()
          : Optional.of(new PolicyView(minutes, version));
    } catch (NumberFormatException notOurs) {
      return Optional.empty();
    }
  }

  private void unavailable(RuntimeException failure) {
    count("error");
    LOG.warn(
        "Redis is unavailable, reading the policy from the database: {}",
        failure.getClass().getSimpleName());
  }

  private void count(String outcome) {
    meters.counter("recovery.policy.cache", "outcome", outcome).increment();
  }
}
