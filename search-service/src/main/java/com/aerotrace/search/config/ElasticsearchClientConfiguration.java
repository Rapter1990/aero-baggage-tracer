package com.aerotrace.search.config;

import co.elastic.clients.transport.rest5_client.low_level.Rest5ClientBuilder;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.apache.hc.client5.http.config.RequestConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.elasticsearch.autoconfigure.Rest5ClientBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Hard limits on every Elasticsearch call. The connection's socket timeout alone does not end a
 * request to a node that accepted the connection and then stopped answering (a frozen or overloaded
 * node): the response timeout does, so an API call becomes {@code 503 SEARCH_UNAVAILABLE} within
 * seconds and an indexing batch is retried instead of hanging.
 */
@Configuration(proxyBeanMethods = false)
class ElasticsearchClientConfiguration {

  @Bean
  Rest5ClientBuilderCustomizer elasticsearchTimeouts(
      @Value("${spring.elasticsearch.socket-timeout:10s}") Duration responseTimeout,
      @Value("${spring.elasticsearch.connection-timeout:2s}") Duration connectTimeout) {
    return new Rest5ClientBuilderCustomizer() {
      @Override
      public void customize(Rest5ClientBuilder builder) {
        // Everything is set on the request configuration below.
      }

      @Override
      public void customize(RequestConfig.Builder requests) {
        requests
            .setResponseTimeout(responseTimeout.toMillis(), TimeUnit.MILLISECONDS)
            .setConnectionRequestTimeout(connectTimeout.toMillis(), TimeUnit.MILLISECONDS);
      }
    };
  }
}
