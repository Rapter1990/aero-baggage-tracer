package com.aerotrace.riskstreams;

import com.aerotrace.common.event.Topics;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.apache.kafka.streams.Topology;
import org.apache.kafka.streams.state.Stores;

/**
 * Builds the risk topology with the Processor API: one source, one processor, three sinks.
 *
 * <p>Partitioning: the source topic is keyed by bagTag, so every scan of a bag (and so of each of
 * its legs) reaches the same task and per-bag ordering holds. The stores are partitioned the same
 * way, so the task count equals the partition count of {@code baggage.scan.received.v1} (see the
 * README for the number and why it cannot be reduced later).
 */
public final class RiskStreamsTopology {

  /** Durable (changelog-backed) store of seen scanEventIds. */
  public static final String DEDUP_STORE = "scan-dedup-store";

  /** Latest legitimate checkpoint, departure, airport, loaded flag and level per leg. */
  public static final String LEG_STORE = "leg-state-store";

  /** Active legs ordered by eviction time, so the punctuator range-scans only what is due. */
  public static final String DEPARTURE_INDEX_STORE = "departure-index-store";

  /** Rejected-transition counts per bagTag and tumbling event-time window. */
  public static final String ANOMALY_STORE = "anomaly-window-store";

  public static final String EVENT_TYPE_RISK_CHANGED = "baggage.risk.changed";
  public static final String EVENT_TYPE_SCAN_ANOMALY = "baggage.scan.anomaly";
  public static final String EVENT_TYPE_DEAD_LETTER = "baggage.dead-letter";

  /** Schema revision of the three output events. */
  public static final int OUTPUT_SCHEMA_VERSION = 1;

  /** Dead-letter reason codes. */
  public static final String REASON_MALFORMED_JSON = "MALFORMED_JSON";

  public static final String REASON_UNSUPPORTED_SCHEMA_VERSION = "UNSUPPORTED_SCHEMA_VERSION";
  public static final String REASON_INVALID_PAYLOAD = "INVALID_PAYLOAD";

  private RiskStreamsTopology() {}

  /**
   * @param props thresholds, grace, windows and intervals
   * @param clock the wall clock: "now" of the risk evaluation, the punctuator and publishedAt
   * @param metrics meters updated by the topology
   */
  public static Topology build(
      RiskStreamsProperties props, Clock clock, RiskStreamsMetrics metrics) {
    Topology topology = new Topology();
    topology.addSource(
        "scan-source", new StringDeserializer(), new StringDeserializer(), Topics.SCAN_RECEIVED);
    topology.addProcessor(
        "risk-processor", () -> new ScanProcessor(props, clock, metrics), "scan-source");

    // Every store keeps its changelog (default <application.id>-<store>-changelog) so that state
    // survives a restart and a rebalance; this is what makes deduplication durable.
    topology.addStateStore(
        Stores.windowStoreBuilder(
                Stores.persistentWindowStore(
                    DEDUP_STORE, props.dedupRetention(), Duration.ofMillis(1), false),
                Serdes.String(),
                Serdes.Long())
            .withLoggingEnabled(Map.of()),
        "risk-processor");
    topology.addStateStore(
        Stores.keyValueStoreBuilder(
                Stores.persistentKeyValueStore(LEG_STORE),
                Serdes.String(),
                new JsonSerde<>(LegState.class))
            .withLoggingEnabled(Map.of()),
        "risk-processor");
    topology.addStateStore(
        Stores.keyValueStoreBuilder(
                Stores.persistentKeyValueStore(DEPARTURE_INDEX_STORE),
                Serdes.String(),
                Serdes.String())
            .withLoggingEnabled(Map.of()),
        "risk-processor");
    topology.addStateStore(
        Stores.windowStoreBuilder(
                Stores.persistentWindowStore(
                    ANOMALY_STORE,
                    props.anomalyWindow().plus(props.grace()),
                    props.anomalyWindow(),
                    false),
                Serdes.String(),
                new JsonSerde<>(AnomalyWindow.class))
            .withLoggingEnabled(Map.of()),
        "risk-processor");

    for (String[] sink :
        new String[][] {
          {ScanProcessor.RISK_SINK, Topics.RISK_CHANGED},
          {ScanProcessor.ANOMALY_SINK, Topics.SCAN_ANOMALY},
          {ScanProcessor.DEAD_LETTER_SINK, Topics.DEAD_LETTER}
        }) {
      topology.addSink(
          sink[0], sink[1], new StringSerializer(), new StringSerializer(), "risk-processor");
    }
    return topology;
  }
}
