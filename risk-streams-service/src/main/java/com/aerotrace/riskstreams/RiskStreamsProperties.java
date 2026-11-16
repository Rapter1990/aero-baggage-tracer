package com.aerotrace.riskstreams;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Tunables of the risk topology.
 *
 * @param highRiskThreshold a leg is HIGH when time to departure is strictly below this (BR-02)
 * @param airportThresholds per-airport overrides of the threshold, keyed by airport code
 * @param grace event-time grace: a record older than (stream time - grace) is late
 * @param anomalyWindow tumbling event-time window of the rejected-transition count (BR-03)
 * @param rejectedThreshold rejected transitions per bagTag and window that raise an anomaly
 * @param punctuationInterval wall-clock interval of the risk re-evaluation
 * @param dedupRetention how long a scanEventId is remembered, counted from its first processing
 * @param maxClockSkew how far past the wall clock an occurredAt may move stream time (the same
 *     tolerance as journey-service's max-clock-skew)
 */
public record RiskStreamsProperties(
    Duration highRiskThreshold,
    Map<String, Duration> airportThresholds,
    Duration grace,
    Duration anomalyWindow,
    int rejectedThreshold,
    Duration punctuationInterval,
    Duration dedupRetention,
    Duration maxClockSkew) {

  public RiskStreamsProperties {
    Objects.requireNonNull(highRiskThreshold, "highRiskThreshold");
    Objects.requireNonNull(grace, "grace");
    Objects.requireNonNull(anomalyWindow, "anomalyWindow");
    Objects.requireNonNull(punctuationInterval, "punctuationInterval");
    Objects.requireNonNull(dedupRetention, "dedupRetention");
    Objects.requireNonNull(maxClockSkew, "maxClockSkew");
    airportThresholds = airportThresholds == null ? Map.of() : Map.copyOf(airportThresholds);
  }

  public static RiskStreamsProperties defaults() {
    return new RiskStreamsProperties(
        Duration.ofMinutes(20),
        Map.of(),
        Duration.ofMinutes(10),
        Duration.ofMinutes(5),
        3,
        Duration.ofSeconds(30),
        Duration.ofDays(7),
        Duration.ofMinutes(5));
  }

  /** Copy with an airport-specific threshold added. */
  public RiskStreamsProperties withAirportThreshold(String airport, Duration threshold) {
    Map<String, Duration> overrides = new HashMap<>(airportThresholds);
    overrides.put(airport, threshold);
    return new RiskStreamsProperties(
        highRiskThreshold,
        overrides,
        grace,
        anomalyWindow,
        rejectedThreshold,
        punctuationInterval,
        dedupRetention,
        maxClockSkew);
  }

  /** The threshold for an airport, falling back to the default one (also for a null airport). */
  public Duration thresholdFor(String airport) {
    return airport == null
        ? highRiskThreshold
        : airportThresholds.getOrDefault(airport, highRiskThreshold);
  }
}
