package com.aerotrace.riskstreams;

import static com.aerotrace.riskstreams.support.Fixtures.at;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aerotrace.common.model.Checkpoint;
import com.aerotrace.riskstreams.support.Fixtures;
import com.aerotrace.riskstreams.support.MutableClock;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.streams.errors.ProcessorStateException;
import org.apache.kafka.streams.processor.api.ProcessorContext;
import org.apache.kafka.streams.processor.api.Record;
import org.apache.kafka.streams.state.KeyValueIterator;
import org.apache.kafka.streams.state.KeyValueStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Infrastructure failures are not poison: they must fail the task, not dead-letter the scan. */
class StoreFailureTest {

  @Test
  @DisplayName("a state store read failure is rethrown, not dead-lettered")
  @SuppressWarnings({"unchecked", "rawtypes"})
  void storeReadFailureIsNotDeadLettered() {
    ProcessorContext<String, String> context = mock(ProcessorContext.class);
    KeyValueStore failing = mock(KeyValueStore.class);
    KeyValueIterator empty = mock(KeyValueIterator.class);
    when(empty.hasNext()).thenReturn(false);
    when(failing.all()).thenReturn(empty);
    when(failing.get(any())).thenThrow(new ProcessorStateException("store is gone"));
    when(context.getStateStore(anyString())).thenReturn(failing);

    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    ScanProcessor processor =
        new ScanProcessor(
            RiskStreamsProperties.defaults(),
            new MutableClock(Fixtures.T0),
            new RiskStreamsMetrics(registry));
    processor.init(context);
    String json = new Fixtures.Scans().scan(Checkpoint.CHECK_IN, at("10:00:00")).json();

    assertThatThrownBy(() -> processor.process(new Record<>("BAG-1", json, 0L)))
        .isInstanceOf(ProcessorStateException.class);

    verify(context, never()).forward(any(Record.class), anyString());
    assertThat(registry.get(RiskStreamsMetrics.PROCESSING_ERRORS).counter().count()).isZero();
  }
}
