package com.aerotrace.journey.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.aerotrace.common.security.Role;
import com.aerotrace.journey.base.AbstractApiTest;
import com.aerotrace.journey.config.JourneyProperties;
import com.aerotrace.journey.constant.MetricNames;
import com.aerotrace.journey.repository.OutboxEventRepository;
import io.micrometer.common.KeyValue;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * What the dashboards read: how many events wait and for how long, how long events take to be
 * acknowledged, and one span per relay run that had work.
 */
class OutboxMetricsTest extends AbstractApiTest {

  /** Remembers every finished observation: its name and its high-cardinality values. */
  private static final class Recorder implements ObservationHandler<Observation.Context> {
    final List<String> names = new ArrayList<>();
    final List<String> values = new ArrayList<>();

    @Override
    public boolean supportsContext(Observation.Context context) {
      return true;
    }

    @Override
    public void onStop(Observation.Context context) {
      names.add(context.getName());
      for (KeyValue value : context.getHighCardinalityKeyValues()) {
        values.add(value.getKey() + "=" + value.getValue());
      }
    }
  }

  @Autowired private OutboxEventRepository outbox;
  @Autowired private JourneyProperties properties;
  @Autowired private Clock clock;
  @Autowired private MeterRegistry meters;
  @Autowired private PlatformTransactionManager transactions;

  @SuppressWarnings("unchecked")
  private static KafkaTemplate<String, String> kafkaThatAcknowledges() {
    KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
    when(kafka.send(anyString(), anyString(), anyString()))
        .thenReturn(CompletableFuture.completedFuture((SendResult<String, String>) null));
    return kafka;
  }

  private OutboxRelay relay(ObservationRegistry observations) {
    return new OutboxRelay(
        outbox, kafkaThatAcknowledges(), properties, clock, meters, observations, transactions);
  }

  private double gauge(String name) {
    return meters.get(name).gauge().value();
  }

  private static void drain(OutboxRelay relay) {
    while (relay.publishPending() > 0) {}
  }

  /** Registers bags; each registration queues its own event (#28), drained by the caller. */
  private List<String> registeredBags(int count) throws Exception {
    List<String> bags = new java.util.ArrayList<>();
    for (int i = 0; i < count; i++) {
      bags.add(registeredBag(newPassengerRef()));
    }
    return bags;
  }

  private void scanOnce(String bag) throws Exception {
    scan(barcodeScan(bag, "IST", "CHECK_IN", 1, minutesAgo(1)), newKey(), as(Role.AGENT))
        .andReturn();
  }

  @Test
  void theGaugesShowTheBacklogAndItsAge() throws Exception {
    OutboxRelay relay = relay(ObservationRegistry.NOOP);
    List<String> bags = registeredBags(2);
    drain(relay);
    assertThat(gauge(MetricNames.OUTBOX_PENDING)).isZero();
    assertThat(gauge(MetricNames.OUTBOX_OLDEST_PENDING_AGE)).isZero();

    scanOnce(bags.get(0));
    scanOnce(bags.get(1));

    assertThat(gauge(MetricNames.OUTBOX_PENDING)).isEqualTo(2);
    assertThat(gauge(MetricNames.OUTBOX_OLDEST_PENDING_AGE)).isGreaterThanOrEqualTo(0);

    drain(relay);

    assertThat(gauge(MetricNames.OUTBOX_PENDING)).isZero();
    assertThat(gauge(MetricNames.OUTBOX_OLDEST_PENDING_AGE)).isZero();
  }

  @Test
  void everyPublishedEventRecordsItsDelayAndTheRunGetsASpan() throws Exception {
    List<String> bags = registeredBags(3);
    drain(relay(ObservationRegistry.NOOP));
    Recorder recorder = new Recorder();
    ObservationRegistry observations = ObservationRegistry.create();
    observations.observationConfig().observationHandler(recorder);
    OutboxRelay relay = relay(observations);
    Timer delay = meters.get(MetricNames.OUTBOX_PUBLISH_DELAY).timer();
    long before = delay.count();
    for (String bag : bags) {
      scanOnce(bag);
    }

    int sent = relay.publishPending();

    assertThat(sent).isEqualTo(3);
    assertThat(delay.count() - before).isEqualTo(3);
    assertThat(delay.totalTime(TimeUnit.MILLISECONDS)).isGreaterThanOrEqualTo(0);
    assertThat(recorder.names).containsExactly(MetricNames.OUTBOX_RELAY);
    assertThat(recorder.values).contains("outbox.events.sent=3", "outbox.events.claimed=3");
  }

  @Test
  void aRunWithNothingToSendHasNoSpan() {
    drain(relay(ObservationRegistry.NOOP));
    Recorder recorder = new Recorder();
    ObservationRegistry observations = ObservationRegistry.create();
    observations.observationConfig().observationHandler(recorder);

    int sent = relay(observations).publishPending();

    assertThat(sent).isZero();
    assertThat(recorder.names).isEmpty();
  }
}
