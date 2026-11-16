package com.aerotrace.riskstreams.support;

import com.aerotrace.common.event.DeadLetterPayload;
import com.aerotrace.common.event.EventEnvelope;
import com.aerotrace.common.event.EventJson;
import com.aerotrace.common.event.RiskChangedPayload;
import com.aerotrace.common.event.ScanAnomalyPayload;
import com.aerotrace.common.event.Topics;
import com.aerotrace.common.model.Checkpoint;
import com.aerotrace.riskstreams.RiskStreamsMetrics;
import com.aerotrace.riskstreams.RiskStreamsProperties;
import com.aerotrace.riskstreams.RiskStreamsTopology;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Properties;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.apache.kafka.streams.KeyValue;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.TestInputTopic;
import org.apache.kafka.streams.TestOutputTopic;
import org.apache.kafka.streams.Topology;
import org.apache.kafka.streams.TopologyTestDriver;

/**
 * Drives the topology under a mutable clock. The wall clock of the driver and the Clock handed to
 * the topology always move together. Scans are piped with the ingest time (the clock) as record
 * timestamp, so the topology has to take event time from the payload's occurredAt.
 */
public final class TopologyHarness implements AutoCloseable {

  public static final String APPLICATION_ID = "risk-streams-test";

  /** One output record: Kafka key plus the parsed envelope. */
  public record Out<T>(String key, EventEnvelope<T> envelope) {
    public T payload() {
      return envelope.payload();
    }
  }

  private final MutableClock clock;
  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
  private final Fixtures.Scans scans = new Fixtures.Scans();
  private final Topology topology;
  private final TopologyTestDriver driver;
  private final Path stateDir;
  private final TestInputTopic<String, String> input;
  private final TestOutputTopic<String, String> riskOut;
  private final TestOutputTopic<String, String> anomalyOut;
  private final TestOutputTopic<String, String> deadLetterOut;

  public TopologyHarness() {
    this(RiskStreamsProperties.defaults(), Fixtures.T0);
  }

  public TopologyHarness(RiskStreamsProperties props) {
    this(props, Fixtures.T0);
  }

  public TopologyHarness(RiskStreamsProperties props, Instant start) {
    this.clock = new MutableClock(start);
    this.topology = RiskStreamsTopology.build(props, clock, new RiskStreamsMetrics(registry));
    try {
      this.stateDir = Files.createTempDirectory("risk-streams-test");
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
    Properties config = new Properties();
    config.put(StreamsConfig.APPLICATION_ID_CONFIG, APPLICATION_ID);
    config.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, "dummy:9092");
    config.put(StreamsConfig.STATE_DIR_CONFIG, stateDir.toString());
    this.driver = new TopologyTestDriver(topology, config, start);
    this.input =
        driver.createInputTopic(
            Topics.SCAN_RECEIVED, new StringSerializer(), new StringSerializer());
    this.riskOut =
        driver.createOutputTopic(
            Topics.RISK_CHANGED, new StringDeserializer(), new StringDeserializer());
    this.anomalyOut =
        driver.createOutputTopic(
            Topics.SCAN_ANOMALY, new StringDeserializer(), new StringDeserializer());
    this.deadLetterOut =
        driver.createOutputTopic(
            Topics.DEAD_LETTER, new StringDeserializer(), new StringDeserializer());
  }

  public Topology topology() {
    return topology;
  }

  public TopologyTestDriver driver() {
    return driver;
  }

  public SimpleMeterRegistry registry() {
    return registry;
  }

  public Instant now() {
    return clock.instant();
  }

  /** A new scan with a unique scanEventId. */
  public ScanBuilder scan(Checkpoint checkpoint, Instant at) {
    return scans.scan(checkpoint, at);
  }

  public void pipe(ScanBuilder... batch) {
    for (ScanBuilder scan : batch) {
      input.pipeInput(scan.bagTag(), scan.json(), clock.instant());
    }
  }

  /** Pipes arbitrary text as the record value (for malformed input). */
  public void pipeRaw(String key, String value) {
    input.pipeInput(key, value, clock.instant());
  }

  /** Moves the clock and the driver's wall clock to the target time (never backwards). */
  public void advanceTo(Instant target) {
    Duration by = Duration.between(clock.instant(), target);
    if (by.isNegative()) {
      throw new IllegalArgumentException("clock cannot go back");
    }
    clock.advance(by);
    driver.advanceWallClockTime(by);
  }

  public List<Out<RiskChangedPayload>> risk() {
    return read(riskOut, RiskChangedPayload.class);
  }

  public List<Out<ScanAnomalyPayload>> anomalies() {
    return read(anomalyOut, ScanAnomalyPayload.class);
  }

  public List<Out<DeadLetterPayload>> deadLetters() {
    return read(deadLetterOut, DeadLetterPayload.class);
  }

  private static <T> List<Out<T>> read(TestOutputTopic<String, String> topic, Class<T> type) {
    return topic.readKeyValuesToList().stream()
        .map((KeyValue<String, String> kv) -> new Out<>(kv.key, EventJson.read(kv.value, type)))
        .toList();
  }

  public double gauge(String name) {
    return registry.get(name).gauge().value();
  }

  public double counter(String name) {
    return registry.get(name).counter().count();
  }

  @Override
  public void close() {
    driver.close();
    try (var files = Files.walk(stateDir)) {
      files.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
    } catch (IOException ignored) {
      // best effort: a temp directory left behind is harmless
    }
  }
}
