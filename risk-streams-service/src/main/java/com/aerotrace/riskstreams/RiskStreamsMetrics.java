package com.aerotrace.riskstreams;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** Micrometer meters of the topology. The meters exist (at 0) as soon as this is constructed. */
public final class RiskStreamsMetrics {

  /** Gauge: wall-clock now minus the event time of the last processed scan, in seconds. */
  public static final String STREAM_LAG_SECONDS = "stream_lag_seconds";

  /** Gauge: number of legs currently HIGH. */
  public static final String BAGGAGE_RISK_HIGH = "baggage_risk_high";

  /** Counter: records past the grace period, excluded from the anomaly window. */
  public static final String LATE_RECORDS = "risk_streams_late_records";

  /** Counter: scans without a leg (legId null), which cannot carry a risk. */
  public static final String SCANS_WITHOUT_LEG = "risk_streams_scans_without_leg";

  private final AtomicLong lagSeconds = new AtomicLong();
  private final AtomicInteger highLegs = new AtomicInteger();
  private final Counter lateRecords;
  private final Counter scansWithoutLeg;

  public RiskStreamsMetrics(MeterRegistry registry) {
    Gauge.builder(STREAM_LAG_SECONDS, lagSeconds, AtomicLong::get).register(registry);
    Gauge.builder(BAGGAGE_RISK_HIGH, highLegs, AtomicInteger::get).register(registry);
    this.lateRecords = Counter.builder(LATE_RECORDS).register(registry);
    this.scansWithoutLeg = Counter.builder(SCANS_WITHOUT_LEG).register(registry);
  }

  public void recordStreamLag(Duration lag) {
    lagSeconds.set(lag.toSeconds());
  }

  public void highRiskLegAdded() {
    highLegs.incrementAndGet();
  }

  public void highRiskLegRemoved() {
    highLegs.decrementAndGet();
  }

  public void lateRecordExcluded() {
    lateRecords.increment();
  }

  public void scanWithoutLeg() {
    scansWithoutLeg.increment();
  }
}
