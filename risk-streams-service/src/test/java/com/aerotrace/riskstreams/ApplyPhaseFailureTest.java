package com.aerotrace.riskstreams;

import static com.aerotrace.riskstreams.support.Fixtures.at;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.aerotrace.common.model.Checkpoint;
import com.aerotrace.common.model.RiskLevel;
import com.aerotrace.riskstreams.support.Fixtures;
import com.aerotrace.riskstreams.support.MutableClock;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.kafka.streams.KeyValue;
import org.apache.kafka.streams.errors.ProcessorStateException;
import org.apache.kafka.streams.processor.PunctuationType;
import org.apache.kafka.streams.processor.Punctuator;
import org.apache.kafka.streams.processor.api.ProcessorContext;
import org.apache.kafka.streams.processor.api.Record;
import org.apache.kafka.streams.state.KeyValueIterator;
import org.apache.kafka.streams.state.KeyValueStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Pins the apply-phase ordering: Kafka-backed writes first, in-memory state (stream time, gauges,
 * counters) last, so a store failure while applying leaves the in-memory state untouched and a
 * retry cannot double-count.
 */
@SuppressWarnings({"unchecked", "rawtypes"})
class ApplyPhaseFailureTest {

  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
  private final ProcessorContext<String, String> context = mock(ProcessorContext.class);
  private final KeyValueStore dedup = mock(KeyValueStore.class);
  private final KeyValueStore legs = mock(KeyValueStore.class);
  private final KeyValueStore index = mock(KeyValueStore.class);
  private final KeyValueStore anomalies = mock(KeyValueStore.class);
  private final AtomicBoolean legPutFails = new AtomicBoolean(true);
  private final Map<String, Object> dedupData = new HashMap<>();

  private ScanProcessor processor() {
    KeyValueIterator<String, String> none = mock(KeyValueIterator.class);
    when(none.hasNext()).thenReturn(false);
    when(index.all()).thenReturn(none);
    when(dedup.get(any())).thenAnswer(i -> dedupData.get(i.getArgument(0)));
    doAnswer(
            i -> {
              dedupData.put(i.getArgument(0), i.getArgument(1));
              return null;
            })
        .when(dedup)
        .put(any(), any());
    doAnswer(
            i -> {
              if (legPutFails.get()) {
                throw new ProcessorStateException("put failed");
              }
              return null;
            })
        .when(legs)
        .put(any(), any());
    when(context.getStateStore(RiskStreamsTopology.DEDUP_STORE)).thenReturn(dedup);
    when(context.getStateStore(RiskStreamsTopology.LEG_STORE)).thenReturn(legs);
    when(context.getStateStore(RiskStreamsTopology.DEPARTURE_INDEX_STORE)).thenReturn(index);
    when(context.getStateStore(RiskStreamsTopology.ANOMALY_STORE)).thenReturn(anomalies);
    ScanProcessor processor =
        new ScanProcessor(
            RiskStreamsProperties.defaults(),
            new MutableClock(Fixtures.T0),
            new RiskStreamsMetrics(registry));
    processor.init(context);
    return processor;
  }

  private double counter(String name) {
    return registry.get(name).counter().count();
  }

  private double gauge(String name) {
    return registry.get(name).gauge().value();
  }

  @Test
  @DisplayName("a store write failing after the dedup puts leaves stream time, gauge and counters")
  void failedApplyLeavesInMemoryStateUntouched() {
    ScanProcessor processor = processor();
    Fixtures.Scans scans = new Fixtures.Scans();
    // HIGH (10 minutes to departure), so the apply step has a gauge change queued; occurredAt
    // 10:04 would move stream time to 10:04 (wall clock 10:00 + 5 minutes of skew).
    String failing =
        scans.scan(Checkpoint.CHECK_IN, at("10:04:00")).departure(at("10:10:00")).json();

    assertThatThrownBy(() -> processor.process(new Record<>("BAG-1", failing, 0L)))
        .isInstanceOf(ProcessorStateException.class);

    assertThat(dedupData).isNotEmpty(); // the dedup puts did succeed before the failure
    assertThat(gauge(RiskStreamsMetrics.BAGGAGE_RISK_HIGH)).isZero();
    assertThat(counter(RiskStreamsMetrics.PROCESSING_ERRORS)).isZero();
    assertThat(counter(RiskStreamsMetrics.SCANS_WITHOUT_LEG)).isZero();

    // Stream time is private; observe it: had it moved to 10:04, a leg-less scan at 09:50 would
    // be older than stream time - 10 minutes grace and count as late.
    legPutFails.set(false);
    String earlier = scans.scan(Checkpoint.CHECK_IN, at("09:50:00")).withoutLeg().json();
    processor.process(new Record<>("BAG-2", earlier, 0L));

    assertThat(counter(RiskStreamsMetrics.LATE_RECORDS)).isZero();
    assertThat(counter(RiskStreamsMetrics.SCANS_WITHOUT_LEG)).isEqualTo(1);
  }

  @Test
  @DisplayName("a failing leg write in the punctuator does not change highLegs or the gauge")
  void failedPunctuatorWriteLeavesGaugeUntouched() {
    processor();
    ArgumentCaptor<Punctuator> punctuator = ArgumentCaptor.forClass(Punctuator.class);
    org.mockito.Mockito.verify(context)
        .schedule(any(Duration.class), any(PunctuationType.class), punctuator.capture());

    String legKey = "BAG-1:LEG-1";
    LegState quiet =
        new LegState(
            "BAG-1",
            "LEG-1",
            "IST",
            Checkpoint.CHECK_IN,
            at("09:55:00"),
            at("10:10:00"),
            false,
            RiskLevel.LOW,
            at("10:10:00"));
    KeyValueIterator<String, String> due = mock(KeyValueIterator.class);
    when(due.hasNext()).thenReturn(true, false);
    when(due.next())
        .thenReturn(
            new KeyValue<>("%019d|%s".formatted(at("10:10:00").toEpochMilli(), legKey), legKey));
    when(index.range(anyString(), anyString())).thenReturn(due);
    when(legs.get(legKey)).thenReturn(quiet);

    assertThatThrownBy(() -> punctuator.getValue().punctuate(0L))
        .isInstanceOf(ProcessorStateException.class);

    assertThat(gauge(RiskStreamsMetrics.BAGGAGE_RISK_HIGH)).isZero();
  }
}
