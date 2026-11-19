package com.aerotrace.search.config;

import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SearchProperties.class)
class SearchConfiguration {

  /** The single source of time, so cursors, lag and reports can be tested with a fake. */
  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }
}
