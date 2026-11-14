package com.aerotrace.gateway.ratelimit;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

/**
 * Token bucket kept in Redis so every gateway instance shares one bucket per caller. The refill and
 * take happen in one Lua script, so concurrent requests cannot both spend the same token.
 *
 * <p>Fails open: if Redis is unreachable the request is allowed (and a warning is logged), because
 * a cache outage should not take the whole platform's entry point down.
 */
public class RedisRateLimiter implements RateLimiter {

  private static final Logger LOG = LoggerFactory.getLogger(RedisRateLimiter.class);

  private static final String LUA =
      """
      local tokens_key = KEYS[1]
      local timestamp_key = KEYS[2]
      local rate = tonumber(ARGV[1])
      local capacity = tonumber(ARGV[2])
      local now = tonumber(ARGV[3])
      local requested = tonumber(ARGV[4])
      local ttl = math.max(1, math.floor((capacity / rate) * 2))

      local last_tokens = tonumber(redis.call('get', tokens_key))
      if last_tokens == nil then last_tokens = capacity end
      local last_refreshed = tonumber(redis.call('get', timestamp_key))
      if last_refreshed == nil then last_refreshed = 0 end

      local filled = math.min(capacity, last_tokens + (math.max(0, now - last_refreshed) * rate))
      local allowed = filled >= requested
      local new_tokens = filled
      if allowed then new_tokens = filled - requested end

      redis.call('setex', tokens_key, ttl, new_tokens)
      redis.call('setex', timestamp_key, ttl, now)
      local retry_after = 0
      if not allowed then retry_after = math.ceil((requested - filled) / rate) end
      return { allowed and 1 or 0, math.floor(new_tokens), retry_after }
      """;

  @SuppressWarnings("rawtypes")
  private static final DefaultRedisScript<List> SCRIPT = new DefaultRedisScript<>(LUA, List.class);

  private final StringRedisTemplate redis;
  private final double replenishRate;
  private final int burstCapacity;

  /** The refill rate is in tokens per second and may be a fraction (0.2 is one token every 5 s). */
  public RedisRateLimiter(StringRedisTemplate redis, double replenishRate, int burstCapacity) {
    this.redis = redis;
    this.replenishRate = replenishRate;
    this.burstCapacity = burstCapacity;
  }

  @Override
  public Decision tryAcquire(String key) {
    try {
      // The hash tag keeps both keys of one caller in the same Redis Cluster slot.
      List<String> keys = List.of("rl:{" + key + "}:tokens", "rl:{" + key + "}:ts");
      List<?> result =
          redis.execute(
              SCRIPT,
              keys,
              String.valueOf(replenishRate),
              String.valueOf(burstCapacity),
              String.valueOf(System.currentTimeMillis() / 1000.0),
              "1");
      if (result == null || result.size() < 2) {
        return new Decision(true, -1);
      }
      boolean allowed = ((Number) result.get(0)).longValue() == 1L;
      long retryAfter =
          result.size() > 2 ? ((Number) result.get(2)).longValue() : (allowed ? 0 : 1);
      return new Decision(allowed, ((Number) result.get(1)).longValue(), retryAfter);
    } catch (RuntimeException redisUnavailable) {
      LOG.warn("Rate limiter unavailable, allowing request: {}", redisUnavailable.getMessage());
      return new Decision(true, -1);
    }
  }
}
