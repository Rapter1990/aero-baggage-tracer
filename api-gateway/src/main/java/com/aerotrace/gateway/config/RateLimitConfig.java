package com.aerotrace.gateway.config;

import com.aerotrace.gateway.ratelimit.AuthRateLimiter;
import com.aerotrace.gateway.ratelimit.ClientAddressResolver;
import com.aerotrace.gateway.ratelimit.RateLimiter;
import com.aerotrace.gateway.ratelimit.RedisRateLimiter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

/** Redis-backed token buckets shared by all gateway instances, and the client address rules. */
@Configuration(proxyBeanMethods = false)
class RateLimitConfig {

  @Bean
  RateLimiter redisRateLimiter(StringRedisTemplate redis, GatewayProperties properties) {
    return new RedisRateLimiter(
        redis, properties.rateLimit().replenishRate(), properties.rateLimit().burstCapacity());
  }

  /** The stricter bucket of the authentication endpoints (see {@code RateLimitFilter}). */
  @Bean
  AuthRateLimiter authRateLimiter(StringRedisTemplate redis, GatewayProperties properties) {
    GatewayProperties.Auth auth = properties.rateLimit().auth();
    return new AuthRateLimiter(
        new RedisRateLimiter(redis, auth.replenishRate(), auth.burstCapacity()));
  }

  /** Believes {@code X-Forwarded-For} only from the configured trusted proxies. */
  @Bean
  ClientAddressResolver clientAddressResolver(GatewayProperties properties) {
    return new ClientAddressResolver(properties.trustedProxies());
  }
}
