package com.aerotrace.gateway.config;

import com.aerotrace.gateway.ratelimit.RateLimiter;
import com.aerotrace.gateway.ratelimit.RedisRateLimiter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

/** Redis-backed token bucket shared by all gateway instances. */
@Configuration(proxyBeanMethods = false)
class RateLimitConfig {

  @Bean
  RateLimiter redisRateLimiter(StringRedisTemplate redis, GatewayProperties properties) {
    return new RedisRateLimiter(
        redis, properties.rateLimit().replenishRate(), properties.rateLimit().burstCapacity());
  }
}
