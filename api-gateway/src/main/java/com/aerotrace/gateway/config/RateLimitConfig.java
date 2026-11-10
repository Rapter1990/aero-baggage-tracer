package com.aerotrace.gateway.config;

import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Redis-backed token bucket shared by all gateway instances. */
@Configuration(proxyBeanMethods = false)
class RateLimitConfig {

  // Same bean name as the auto-configured one, which backs off; this one carries default limits.
  @Bean
  RedisRateLimiter redisRateLimiter(GatewayProperties properties) {
    return new RedisRateLimiter(
        properties.rateLimit().replenishRate(), properties.rateLimit().burstCapacity());
  }
}
