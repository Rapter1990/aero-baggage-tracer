package com.aerotrace.riskstreams;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class RiskStreamsPropertiesTest {

  @Test
  void defaultsMatchTheDesign() {
    RiskStreamsProperties props = RiskStreamsProperties.defaults();

    assertThat(props.highRiskThreshold()).isEqualTo(Duration.ofMinutes(20));
    assertThat(props.grace()).isEqualTo(Duration.ofMinutes(10));
    assertThat(props.anomalyWindow()).isEqualTo(Duration.ofMinutes(5));
    assertThat(props.rejectedThreshold()).isEqualTo(3);
    assertThat(props.punctuationInterval()).isEqualTo(Duration.ofSeconds(30));
    assertThat(props.dedupRetention()).isEqualTo(Duration.ofDays(7));
    assertThat(props.airportThresholds()).isEmpty();
  }

  @Test
  void thresholdFallsBackToTheDefault() {
    RiskStreamsProperties props =
        RiskStreamsProperties.defaults().withAirportThreshold("IST", Duration.ofMinutes(45));

    assertThat(props.thresholdFor("IST")).isEqualTo(Duration.ofMinutes(45));
    assertThat(props.thresholdFor("SAW")).isEqualTo(Duration.ofMinutes(20));
    assertThat(props.thresholdFor(null)).isEqualTo(Duration.ofMinutes(20));
  }
}
