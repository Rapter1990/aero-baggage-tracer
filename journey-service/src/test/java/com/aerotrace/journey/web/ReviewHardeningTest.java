package com.aerotrace.journey.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aerotrace.common.security.Role;
import com.aerotrace.journey.base.AbstractApiTest;
import com.aerotrace.journey.config.JourneyProperties;
import com.aerotrace.journey.outbox.OutboxRelay;
import com.aerotrace.journey.repository.OutboxEventRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import javax.sql.DataSource;
import org.apache.kafka.common.errors.RecordTooLargeException;
import org.apache.kafka.common.errors.TimeoutException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Behaviour added after the code review: an Idempotency-Key cannot swallow a different scan, equal
 * event times are decided by the device sequence, a journey lists a bounded history, and the relay
 * keeps order (one relay at a time) and sets aside events the broker refuses for good.
 */
class ReviewHardeningTest extends AbstractApiTest {

  @Autowired private OutboxEventRepository outbox;
  @Autowired private JourneyProperties properties;
  @Autowired private Clock clock;
  @Autowired private MeterRegistry meters;
  @Autowired private PlatformTransactionManager transactions;
  @Autowired private DataSource dataSource;

  @SuppressWarnings("unchecked")
  private OutboxRelay relayWith(KafkaTemplate<String, String> kafka) {
    return new OutboxRelay(outbox, kafka, properties, clock, meters, transactions);
  }

  @SuppressWarnings("unchecked")
  private static KafkaTemplate<String, String> kafkaFailingFor(String key, Throwable failure) {
    KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
    when(kafka.send(anyString(), anyString(), anyString()))
        .thenReturn(CompletableFuture.completedFuture((SendResult<String, String>) null));
    when(kafka.send(anyString(), eq(key), anyString()))
        .thenReturn(CompletableFuture.failedFuture(failure));
    return kafka;
  }

  private void drain() {
    OutboxRelay all = relayWith(kafkaFailingFor("none", new IllegalStateException()));
    while (all.publishPending() > 0) {}
  }

  @Test
  void aKeyReusedForAnotherBagOrScanIsAConflictAndRevealsNothing() throws Exception {
    String bagA = registeredBag(newPassengerRef());
    String bagB = registeredBag(newPassengerRef());
    String key = newKey();
    OffsetDateTime at = minutesAgo(1);
    scan(barcodeScan(bagA, "IST", "CHECK_IN", 1, at), key, as(Role.AGENT))
        .andExpect(status().isAccepted());

    String body =
        scan(barcodeScan(bagB, "IST", "CHECK_IN", 1, at), key, as(Role.AGENT))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(body).doesNotContain(bagA);
    scan(barcodeScan(bagA, "IST", "SORTING", 1, at), key, as(Role.AGENT))
        .andExpect(status().isConflict());
    // The honest retry of the first scan still gets its answer.
    scan(barcodeScan(bagA, "IST", "CHECK_IN", 1, at), key, as(Role.AGENT))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.data.duplicate").value(true));
    assertThat(count("SELECT count(*) FROM journey.scan_audit WHERE bag_tag = ?", bagB)).isZero();
  }

  @Test
  void equalEventTimesAreDecidedByTheDeviceSequenceNotByArrival() throws Exception {
    String bag = registeredBag(newPassengerRef());
    OffsetDateTime at = minutesAgo(2);

    scan(barcodeScan(bag, "IST", "LOADED", 5, at), newKey(), as(Role.AGENT)).andReturn();
    scan(barcodeScan(bag, "IST", "SORTING", 3, at), newKey(), as(Role.AGENT)).andReturn();
    journey(bag, as(Role.AGENT))
        .andExpect(jsonPath("$.data.latestState.checkpoint").value("LOADED"));

    scan(barcodeScan(bag, "IST", "ARRIVED", 6, at), newKey(), as(Role.AGENT)).andReturn();
    journey(bag, as(Role.AGENT))
        .andExpect(jsonPath("$.data.latestState.checkpoint").value("ARRIVED"))
        .andExpect(jsonPath("$.data.history.length()").value(3));
  }

  @Test
  void aJourneyListsAtMostTheNewestScansAndSaysWhenOlderOnesAreLeftOut() throws Exception {
    String bag = registeredBag(newPassengerRef());
    execute(
        "INSERT INTO journey.scan_audit (id, event_id, bag_tag, protocol, airport, checkpoint,"
            + " device_sequence, occurred_at, device_timestamp, source_timezone, ingested_at)"
            + " SELECT gen_random_uuid(), :bag || '-' || g, :bag, 'BARCODE', 'IST', 'SORTING',"
            + " g, now() - make_interval(mins => 1000 - g), now(), '+00:00', now()"
            + " FROM generate_series(1, 501) g",
        Map.of("bag", bag));

    var data = body(journey(bag, as(Role.AGENT))).path("data");

    assertThat(data.path("history").size()).isEqualTo(500);
    assertThat(data.path("historyTruncated").asBoolean()).isTrue();
    // In event order, and the oldest of the 501 is the one that was left out.
    assertThat(data.path("history").get(0).path("deviceSequence").asLong()).isEqualTo(2);
    assertThat(data.path("history").get(499).path("deviceSequence").asLong()).isEqualTo(501);
  }

  @Test
  void aScanPayloadWithTooManyFieldsIsRefused() throws Exception {
    String bag = registeredBag(newPassengerRef());
    Map<String, Object> body = barcodeScan(bag, "IST", "CHECK_IN", 1, minutesAgo(1));
    Map<String, Object> payload = new HashMap<>((Map<String, Object>) body.get("payload"));
    for (int i = 0; i < 25; i++) {
      payload.put("extra" + i, "x");
    }
    body.put("payload", payload);

    scan(body, newKey(), as(Role.AGENT)).andExpect(status().isBadRequest());
  }

  @Test
  void anEventTheBrokerRefusesForGoodIsSetAsideAndTheOnesBehindItAreStillSent() throws Exception {
    drain();
    String poison = bagWithOneScan();
    String healthy = bagWithOneScan();

    int sent =
        relayWith(kafkaFailingFor(poison, new RecordTooLargeException("too large")))
            .publishPending();

    assertThat(sent).isEqualTo(1);
    assertThat(
            count(
                "SELECT count(*) FROM journey.outbox_event WHERE message_key = ?"
                    + " AND parked_at IS NOT NULL AND published_at IS NULL",
                poison))
        .isEqualTo(1);
    assertThat(
            count(
                "SELECT count(*) FROM journey.outbox_event WHERE message_key = ?"
                    + " AND published_at IS NOT NULL",
                healthy))
        .isEqualTo(1);
    // The parked row is never claimed again.
    assertThat(relayWith(kafkaFailingFor("none", new IllegalStateException())).publishPending())
        .isZero();
  }

  @Test
  void aTemporaryBrokerProblemKeepsTheEventAndStopsTheRun() throws Exception {
    drain();
    String first = bagWithOneScan();
    String second = bagWithOneScan();

    int sent =
        relayWith(kafkaFailingFor(first, new TimeoutException("broker slow"))).publishPending();

    assertThat(sent).isZero();
    assertThat(
            count(
                "SELECT count(*) FROM journey.outbox_event WHERE message_key = ?"
                    + " AND published_at IS NULL AND parked_at IS NULL AND attempts = 1",
                first))
        .isEqualTo(1);
    // The later event must not overtake the one that failed.
    assertThat(
            count(
                "SELECT count(*) FROM journey.outbox_event WHERE message_key = ?"
                    + " AND published_at IS NOT NULL",
                second))
        .isZero();
    drain();
  }

  @Test
  void onlyOneRelayRunsAtATimeSoReplicasKeepTheOrder() throws Exception {
    drain();
    String bag = bagWithOneScan();

    try (Connection other = dataSource.getConnection();
        Statement statement = other.createStatement()) {
      // Another replica is in the middle of its run (it holds the relay lock).
      statement.execute("SELECT pg_advisory_lock(7410001)");
      try {
        assertThat(relayWith(kafkaFailingFor("none", new IllegalStateException())).publishPending())
            .isZero();
        assertThat(
                count(
                    "SELECT count(*) FROM journey.outbox_event WHERE message_key = ?"
                        + " AND published_at IS NULL",
                    bag))
            .isEqualTo(1);
      } finally {
        statement.execute("SELECT pg_advisory_unlock(7410001)");
      }
    }
    assertThat(relayWith(kafkaFailingFor("none", new IllegalStateException())).publishPending())
        .isEqualTo(1);
  }

  private String bagWithOneScan() throws Exception {
    String bag = registeredBag(newPassengerRef());
    scan(barcodeScan(bag, "IST", "CHECK_IN", 1, minutesAgo(1)), newKey(), as(Role.AGENT))
        .andExpect(status().isAccepted());
    return bag;
  }
}
