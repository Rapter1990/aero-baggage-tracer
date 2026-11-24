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

  /**
   * The application clock, for envelope metadata only (a dead letter's publishedAt, a consumer dead
   * letter's occurredAt). Delivery, leases and retention use the database's now() (ADR 0005).
   */
  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }
}
