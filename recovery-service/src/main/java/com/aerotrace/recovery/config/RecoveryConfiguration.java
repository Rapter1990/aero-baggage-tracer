package com.aerotrace.recovery.config;

import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RecoveryProperties.class)
class RecoveryConfiguration {

  /** The single source of time, so transitions and the outbox can be tested with a fake. */
  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }
}
