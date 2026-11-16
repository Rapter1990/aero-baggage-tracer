package com.aerotrace.riskstreams;

import java.time.Duration;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuration bound from {@code aerotrace.risk.*}.
 *
 * @param thresholdMinutes default HIGH threshold in minutes
 * @param airportThresholds per-airport threshold in minutes, keyed by airport code
 */
@ConfigurationProperties("aerotrace.risk")
public record RiskStreamsSettings(
    @DefaultValue("20") long thresholdMinutes,
    Map<String, Long> airportThresholds,
    @DefaultValue("10m") Duration grace,
    @DefaultValue("5m") Duration anomalyWindow,
    @DefaultValue("3") int rejectedThreshold,
    @DefaultValue("30s") Duration punctuationInterval,
    @DefaultValue("7d") Duration dedupRetention) {

  /** The topology's tunables. */
  public RiskStreamsProperties toProperties() {
    Map<String, Duration> overrides = new java.util.HashMap<>();
    if (airportThresholds != null) {
      airportThresholds.forEach(
          (airport, minutes) -> overrides.put(airport, Duration.ofMinutes(minutes)));
    }
    return new RiskStreamsProperties(
        Duration.ofMinutes(thresholdMinutes),
        overrides,
        grace,
        anomalyWindow,
        rejectedThreshold,
        punctuationInterval,
        dedupRetention);
  }
}
