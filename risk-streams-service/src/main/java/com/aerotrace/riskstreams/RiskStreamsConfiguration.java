package com.aerotrace.riskstreams;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.kafka.KafkaStreamsMetrics;
import java.time.Clock;
import java.util.Properties;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.KafkaStreams;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.Topology;
import org.apache.kafka.streams.errors.StreamsUncaughtExceptionHandler;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires the topology, the KafkaStreams instance, its lifecycle, metrics and health. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RiskStreamsSettings.class)
class RiskStreamsConfiguration {

  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }

  @Bean
  RiskStreamsMetrics riskStreamsMetrics(MeterRegistry registry) {
    return new RiskStreamsMetrics(registry);
  }

  @Bean
  Topology riskTopology(RiskStreamsSettings settings, Clock clock, RiskStreamsMetrics metrics) {
    return RiskStreamsTopology.build(settings.toProperties(), clock, metrics);
  }

  /**
   * Streams settings: application.id, bootstrap servers and state dir come from {@code
   * spring.kafka.*}; exactly-once v2 and String serdes are fixed here.
   */
  static Properties streamsConfig(KafkaProperties kafka) {
    Properties config = new Properties();
    config.putAll(kafka.buildStreamsProperties());
    config.put(StreamsConfig.PROCESSING_GUARANTEE_CONFIG, StreamsConfig.EXACTLY_ONCE_V2);
    config.put(StreamsConfig.DEFAULT_KEY_SERDE_CLASS_CONFIG, Serdes.StringSerde.class.getName());
    config.put(StreamsConfig.DEFAULT_VALUE_SERDE_CLASS_CONFIG, Serdes.StringSerde.class.getName());
    return config;
  }

  @Bean
  KafkaStreams riskKafkaStreams(Topology topology, KafkaProperties kafka, MeterRegistry registry) {
    KafkaStreams streams = new KafkaStreams(topology, streamsConfig(kafka));
    // A failed stream thread is replaced instead of taking the whole instance down.
    streams.setUncaughtExceptionHandler(
        exception -> StreamsUncaughtExceptionHandler.StreamThreadExceptionResponse.REPLACE_THREAD);
    // Consumer lag, rebalance and state-store (RocksDB) metrics of the embedded clients.
    new KafkaStreamsMetrics(streams).bindTo(registry);
    return streams;
  }

  @Bean
  SmartLifecycle riskStreamsLifecycle(KafkaStreams streams) {
    return new StreamsLifecycle(streams);
  }

  @Bean
  HealthIndicator riskStreamsHealth(KafkaStreams streams) {
    return () -> health(streams.state());
  }

  static Health health(KafkaStreams.State state) {
    Health.Builder builder =
        state == KafkaStreams.State.RUNNING || state == KafkaStreams.State.REBALANCING
            ? Health.up()
            : Health.down();
    return builder.withDetail("state", state.name()).build();
  }

  /** Starts the streams after the context is ready and closes them (bounded) on shutdown. */
  static final class StreamsLifecycle implements SmartLifecycle {

    private final KafkaStreams streams;
    private volatile boolean running;

    StreamsLifecycle(KafkaStreams streams) {
      this.streams = streams;
    }

    @Override
    public void start() {
      streams.start();
      running = true;
    }

    @Override
    public void stop() {
      streams.close(java.time.Duration.ofSeconds(30));
      running = false;
    }

    @Override
    public boolean isRunning() {
      return running;
    }
  }
}
