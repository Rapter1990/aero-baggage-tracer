package com.aerotrace.riskstreams;

import com.aerotrace.common.event.Topics;
import java.time.Clock;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.apache.kafka.streams.Topology;
import org.apache.kafka.streams.processor.api.Processor;
import org.apache.kafka.streams.processor.api.ProcessorSupplier;
import org.apache.kafka.streams.processor.api.Record;

/**
 * Builds the risk topology with the Processor API.
 *
 * <p>STUB (stage 2): wires the source and the three sinks but processes nothing. Stage 3 replaces
 * the no-op processor with dedup, leg risk, anomaly counting and dead-lettering.
 */
public final class RiskStreamsTopology {

  /** Durable (changelog-backed) store of seen scanEventIds. */
  public static final String DEDUP_STORE = "scan-dedup-store";

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
    ProcessorSupplier<String, String, String, String> noop =
        () ->
            new Processor<>() {
              @Override
              public void process(Record<String, String> record) {
                // stub: stage 3 implements the topology
              }
            };
    topology.addProcessor("risk-processor", noop, "scan-source");
    topology.addSink(
        "risk-sink",
        Topics.RISK_CHANGED,
        new StringSerializer(),
        new StringSerializer(),
        "risk-processor");
    topology.addSink(
        "anomaly-sink",
        Topics.SCAN_ANOMALY,
        new StringSerializer(),
        new StringSerializer(),
        "risk-processor");
    topology.addSink(
        "dead-letter-sink",
        Topics.DEAD_LETTER,
        new StringSerializer(),
        new StringSerializer(),
        "risk-processor");
    return topology;
  }
}
