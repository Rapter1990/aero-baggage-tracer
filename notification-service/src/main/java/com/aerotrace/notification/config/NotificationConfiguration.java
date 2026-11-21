package com.aerotrace.notification.config;

import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(NotificationProperties.class)
class NotificationConfiguration {

  /** The single source of time, so delivery and retention can be tested with a fake. */
  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }
}
