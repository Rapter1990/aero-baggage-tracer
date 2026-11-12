package com.aerotrace.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.aerotrace.gateway.support.GatewayTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

/**
 * The password the gateway is configured with is the one its Redis connections authenticate with.
 */
class RedisPasswordWiringTest extends GatewayTestBase {

  @Autowired private RedisConnectionFactory connectionFactory;

  @Test
  void redisConnectionsUseTheConfiguredPassword() {
    LettuceConnectionFactory lettuce = (LettuceConnectionFactory) connectionFactory;

    assertThat(lettuce.getStandaloneConfiguration().getPassword().toOptional())
        .contains("unused-in-tests".toCharArray());
  }
}
