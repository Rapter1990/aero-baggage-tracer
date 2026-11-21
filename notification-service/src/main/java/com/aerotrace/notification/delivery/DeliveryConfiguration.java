package com.aerotrace.notification.delivery;

import com.aerotrace.notification.config.NotificationProperties;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class DeliveryConfiguration {

  @Bean
  BackoffPolicy backoffPolicy(NotificationProperties properties) {
    NotificationProperties.Delivery delivery = properties.delivery();
    return new BackoffPolicy(
        delivery.baseDelay(),
        delivery.maxDelay(),
        delivery.jitter(),
        () -> ThreadLocalRandom.current().nextDouble());
  }
}
