package com.aerotrace.gateway.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aerotrace.gateway.support.AbstractTestContainerConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/** Runs the real Lua token bucket against a real Redis. Skipped when Docker is unavailable. */
class RedisRateLimiterIT extends AbstractTestContainerConfiguration {

  private static LettuceConnectionFactory connections;
  private static StringRedisTemplate template;

  @BeforeAll
  static void connectToRedis() {
    connections = connect(redisHost(), redisPort(), REDIS_PASSWORD);
    template = new StringRedisTemplate(connections);
    template.afterPropertiesSet();
  }

  @AfterAll
  static void disconnect() {
    if (connections != null) {
      connections.destroy();
    }
  }

  private static RedisStandaloneConfiguration configuration(
      String host, int port, String password) {
    RedisStandaloneConfiguration configuration = new RedisStandaloneConfiguration(host, port);
    if (password != null) {
      configuration.setPassword(password);
    }
    return configuration;
  }

  private static LettuceConnectionFactory connect(String host, int port, String password) {
    LettuceConnectionFactory factory =
        new LettuceConnectionFactory(configuration(host, port, password));
    factory.afterPropertiesSet();
    return factory;
  }

  @Test
  void allowsTheBurstThenRejects() {
    // A slow refill makes the outcome independent of test timing.
    RedisRateLimiter limiter = new RedisRateLimiter(template, 1, 3);

    assertThat(limiter.tryAcquire("burst").allowed()).isTrue();
    assertThat(limiter.tryAcquire("burst").allowed()).isTrue();
    RateLimiter.Decision third = limiter.tryAcquire("burst");
    assertThat(third.allowed()).isTrue();
    assertThat(third.remaining()).isZero();

    RateLimiter.Decision fourth = limiter.tryAcquire("burst");
    assertThat(fourth.allowed()).isFalse();
    assertThat(fourth.remaining()).isZero();
  }

  @Test
  void bucketsAreIndependentPerCaller() {
    RedisRateLimiter limiter = new RedisRateLimiter(template, 1, 1);

    assertThat(limiter.tryAcquire("alice").allowed()).isTrue();
    assertThat(limiter.tryAcquire("alice").allowed()).isFalse();
    assertThat(limiter.tryAcquire("bob").allowed()).isTrue();
  }

  @Test
  void refillsOverTime() throws InterruptedException {
    RedisRateLimiter limiter = new RedisRateLimiter(template, 1, 1);
    // The first call pays for connection setup; do that before timing the bucket.
    limiter.tryAcquire("warm-up");

    assertThat(limiter.tryAcquire("refill").allowed()).isTrue();
    assertThat(limiter.tryAcquire("refill").allowed()).isFalse();
    Thread.sleep(1200);

    assertThat(limiter.tryAcquire("refill").allowed()).isTrue();
  }

  @Test
  void redisRefusesClientsWithoutTheCorrectPassword() {
    for (String password : new String[] {null, "wrong-password"}) {
      LettuceConnectionFactory factory = connect(redisHost(), redisPort(), password);
      try {
        StringRedisTemplate unauthenticated = new StringRedisTemplate(factory);
        unauthenticated.afterPropertiesSet();

        assertThatThrownBy(() -> unauthenticated.opsForValue().get("any-key"))
            .as("password %s", password)
            .isInstanceOf(RuntimeException.class);
      } finally {
        factory.destroy();
      }
    }
  }

  @Test
  void aWrongPasswordMakesTheLimiterFailOpenInsteadOfBlockingTraffic() {
    LettuceConnectionFactory factory = connect(redisHost(), redisPort(), "wrong-password");
    try {
      StringRedisTemplate wrong = new StringRedisTemplate(factory);
      wrong.afterPropertiesSet();

      RateLimiter.Decision decision = new RedisRateLimiter(wrong, 1, 1).tryAcquire("any");

      assertThat(decision.allowed()).isTrue();
      assertThat(decision.remaining()).isEqualTo(-1);
    } finally {
      factory.destroy();
    }
  }

  @Test
  void failsOpenWhenRedisIsUnreachable() {
    LettuceConnectionFactory dead = connect("localhost", 1, null);
    try {
      StringRedisTemplate broken = new StringRedisTemplate(dead);
      broken.afterPropertiesSet();

      RateLimiter.Decision decision = new RedisRateLimiter(broken, 1, 1).tryAcquire("any");

      assertThat(decision.allowed()).isTrue();
      assertThat(decision.remaining()).isEqualTo(-1);
    } finally {
      dead.destroy();
    }
  }
}
