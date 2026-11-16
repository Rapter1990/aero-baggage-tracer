package com.aerotrace.riskstreams;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Map;
import java.util.Properties;
import org.apache.kafka.streams.KafkaStreams;
import org.apache.kafka.streams.StreamsConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;

class RiskStreamsConfigurationTest {

  private static RiskStreamsSettings bind(Map<String, String> source) {
    return new Binder(new MapConfigurationPropertySource(source))
        .bind("aerotrace.risk", RiskStreamsSettings.class)
        .orElseGet(() -> new RiskStreamsSettings(20, null, null, null, 0, null, null));
  }

  @Test
  @DisplayName("settings bind from aerotrace.risk and map to the topology properties")
  void settingsBind() {
    RiskStreamsSettings settings =
        bind(
            Map.of(
                "aerotrace.risk.threshold-minutes", "25",
                "aerotrace.risk.airport-thresholds.IST", "45",
                "aerotrace.risk.grace", "2m",
                "aerotrace.risk.rejected-threshold", "4"));

    RiskStreamsProperties props = settings.toProperties();

    assertThat(props.highRiskThreshold()).isEqualTo(Duration.ofMinutes(25));
    assertThat(props.thresholdFor("IST")).isEqualTo(Duration.ofMinutes(45));
    assertThat(props.grace()).isEqualTo(Duration.ofMinutes(2));
    assertThat(props.rejectedThreshold()).isEqualTo(4);
    assertThat(props.anomalyWindow()).isEqualTo(Duration.ofMinutes(5));
    assertThat(props.punctuationInterval()).isEqualTo(Duration.ofSeconds(30));
    assertThat(props.dedupRetention()).isEqualTo(Duration.ofDays(7));
  }

  @Test
  @DisplayName("without any airport override every airport uses the default")
  void noOverrides() {
    RiskStreamsProperties props =
        bind(Map.of("aerotrace.risk.threshold-minutes", "20")).toProperties();

    assertThat(props.airportThresholds()).isEmpty();
    assertThat(props.thresholdFor("IST")).isEqualTo(Duration.ofMinutes(20));
  }

  @Test
  @DisplayName("streams config is exactly-once v2 with String serdes and the configured id")
  void streamsConfig() {
    KafkaProperties kafka = new KafkaProperties();
    kafka.setBootstrapServers(java.util.List.of("kafka:9092"));
    kafka.getStreams().setApplicationId("risk-streams-service");

    Properties config = RiskStreamsConfiguration.streamsConfig(kafka);

    assertThat(config.get(StreamsConfig.APPLICATION_ID_CONFIG)).isEqualTo("risk-streams-service");
    assertThat(config.get(StreamsConfig.PROCESSING_GUARANTEE_CONFIG))
        .isEqualTo(StreamsConfig.EXACTLY_ONCE_V2);
    assertThat(config.get(StreamsConfig.DEFAULT_KEY_SERDE_CLASS_CONFIG).toString())
        .contains("StringSerde");
    assertThat(config.get(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG).toString())
        .contains("kafka:9092");
  }

  @Test
  @DisplayName("health is UP while running or rebalancing, DOWN otherwise")
  void health() {
    assertThat(RiskStreamsConfiguration.health(KafkaStreams.State.RUNNING).getStatus().getCode())
        .isEqualTo("UP");
    assertThat(
            RiskStreamsConfiguration.health(KafkaStreams.State.REBALANCING).getStatus().getCode())
        .isEqualTo("UP");
    assertThat(RiskStreamsConfiguration.health(KafkaStreams.State.ERROR).getStatus().getCode())
        .isEqualTo("DOWN");
  }
}
