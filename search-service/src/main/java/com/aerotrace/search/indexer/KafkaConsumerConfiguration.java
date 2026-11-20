package com.aerotrace.search.indexer;

import com.aerotrace.search.config.SearchProperties;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;

/**
 * Retry policy of the indexer. Poison events never reach this handler: the indexer dead-letters
 * them itself and goes on (see {@link EventIndexer}). What does reach it is a failure that may pass
 * (Elasticsearch unreachable, 429 or 5xx, the dead-letter topic unreachable): the whole batch is
 * retried with a doubling wait up to {@code max-retry-backoff}, for as long as it takes, and its
 * offsets are not committed meanwhile. Every write is idempotent, so retrying a half-written batch
 * is safe. A healthy event is never dead-lettered because Elasticsearch was down.
 */
@Configuration(proxyBeanMethods = false)
class KafkaConsumerConfiguration {

  private static final Logger LOG = LoggerFactory.getLogger(KafkaConsumerConfiguration.class);

  @Bean
  DefaultErrorHandler kafkaErrorHandler(SearchProperties properties) {
    SearchProperties.Consumers consumers = properties.consumers();
    ExponentialBackOff backOff = new ExponentialBackOff(consumers.retryBackoff().toMillis(), 2.0);
    backOff.setMaxInterval(consumers.maxRetryBackoff().toMillis());
    // No maximum elapsed time: retry until Elasticsearch is back.
    DefaultErrorHandler handler =
        new DefaultErrorHandler(
            (ConsumerRecord<?, ?> record, Exception failure) ->
                // Unreachable with an unlimited back-off; logged in case the policy is changed.
                LOG.error(
                    "Gave up on {}-{}@{}", record.topic(), record.partition(), record.offset()),
            backOff);
    handler.setLogLevel(org.springframework.kafka.KafkaException.Level.WARN);
    return handler;
  }
}
