package com.aerotrace.recovery.config;

import com.aerotrace.recovery.strategy.RecoveryActionSelector;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RecoveryProperties.class)
class RecoveryConfiguration {

  @Bean
  RecoveryActionSelector recoveryActionSelector(RecoveryProperties properties) {
    return RecoveryActionSelector.withDefaults(properties.strategy().minimumExpediteTime());
  }

  /** The single source of time, so transitions and the outbox can be tested with a fake. */
  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }
}
