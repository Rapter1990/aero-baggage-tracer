package com.aerotrace.journey.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.aerotrace.common.event.EventJson;
import com.aerotrace.common.event.ScanReceivedPayload;
import com.aerotrace.common.event.Topics;
import com.aerotrace.common.security.Role;
import com.aerotrace.journey.base.AbstractApiTest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.kafka.KafkaContainer;

/**
 * AT-14 for the journey-service's outbox (issue #15), with a real broker: Kafka becomes unreachable
 * while scans are accepted, the scans are committed and their events stay pending, and when Kafka
 * is back the committed events are published, in the order they were committed. The outage is a
 * paused container: connections hang, as they do when a broker is gone from the network.
 */
class JourneyKafkaOutageTest extends AbstractApiTest {

  private static KafkaContainer kafka;

  @BeforeAll
  static void startKafka() {
    org.junit.jupiter.api.Assumptions.assumeTrue(
        DockerClientFactory.instance().isDockerAvailable(), "Docker is not available");
    synchronized (JourneyKafkaOutageTest.class) {
      if (kafka == null) {
        kafka = new KafkaContainer("apache/kafka:3.9.1");
        kafka.start();
      }
    }
  }

  @DynamicPropertySource
  static void kafkaProperties(DynamicPropertyRegistry registry) {
    registry.add(
        "spring.kafka.bootstrap-servers",
        () -> {
          startKafka();
          return kafka.getBootstrapServers();
        });
    // Short timeouts, so a relay run during the outage gives up quickly.
    registry.add("spring.kafka.producer.properties.max.block.ms", () -> "3000");
    registry.add("spring.kafka.producer.properties.request.timeout.ms", () -> "3000");
    registry.add("spring.kafka.producer.properties.delivery.timeout.ms", () -> "6000");
  }

  @Autowired private OutboxRelay relay;

  /** Never leave the broker paused for the tests that run after this one. */
  @AfterEach
  void brokerIsBack() {
    resume();
  }

  private static void pause() {
    DockerClientFactory.instance().client().pauseContainerCmd(kafka.getContainerId()).exec();
  }

  private static void resume() {
    try {
      DockerClientFactory.instance().client().unpauseContainerCmd(kafka.getContainerId()).exec();
    } catch (RuntimeException notPaused) {
      // Already running.
    }
  }

  private List<ConsumerRecord<String, String>> scanRecordsFor(String bagTag, int expected) {
    Properties config = new Properties();
    config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
    config.put(ConsumerConfig.GROUP_ID_CONFIG, "journey-outage-" + UUID.randomUUID());
    config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
    config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
    config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
    List<ConsumerRecord<String, String>> found = new ArrayList<>();
    try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(config)) {
      consumer.subscribe(List.of(Topics.SCAN_RECEIVED));
      long deadline = System.currentTimeMillis() + 30_000;
      long quietUntil = 0;
      while (System.currentTimeMillis() < deadline) {
        for (ConsumerRecord<String, String> r : consumer.poll(Duration.ofMillis(500))) {
          if (bagTag.equals(r.key())) {
            found.add(r);
          }
        }
        if (found.size() >= expected) {
          // Keep listening a little longer: a duplicate would show up here.
          if (quietUntil == 0) {
            quietUntil = System.currentTimeMillis() + 3_000;
          } else if (System.currentTimeMillis() > quietUntil) {
            break;
          }
        }
      }
    }
    return found;
  }

  @Test
  @DisplayName(
      "AT-14 Kafka is down while scans are accepted: they commit, and their events are published"
          + " in order once Kafka is back")
  void committedScanEventsAreReplayedAfterTheOutage() throws Exception {
    while (relay.publishPending() > 0) {}
    String bagTag = registeredBag(newPassengerRef());
    // The registration event is published before the outage starts.
    while (relay.publishPending() > 0) {}

    pause();
    for (int i = 1; i <= 3; i++) {
      Map<String, Object> body = barcodeScan(bagTag, "IST", "CHECK_IN", i, minutesAgo(5 - i));
      scan(body, newKey(), as(Role.AGENT)).andReturn();
    }

    // Publishing fails, and nothing is lost: the three scan events are still pending.
    relay.publishPending();
    assertThat(
            count(
                "SELECT count(*) FROM journey.outbox_event WHERE message_key = ?"
                    + " AND published_at IS NULL AND topic = ?",
                bagTag,
                Topics.SCAN_RECEIVED))
        .isEqualTo(3);

    // Kafka is back: the committed events are replayed.
    resume();
    await()
        .atMost(Duration.ofSeconds(90))
        .pollInterval(Duration.ofSeconds(1))
        .untilAsserted(
            () -> {
              relay.publishPending();
              assertThat(
                      count(
                          "SELECT count(*) FROM journey.outbox_event WHERE message_key = ?"
                              + " AND published_at IS NULL",
                          bagTag))
                  .isZero();
            });
    // More relay runs publish nothing again.
    assertThat(relay.publishPending()).isZero();

    // At least once: an attempt the outage cut off can still reach the broker when it resumes, so
    // an event may be written twice with the same id (consumers dedupe on it). What must hold is
    // three distinct events, none more than twice, in the order the scans were committed.
    List<ConsumerRecord<String, String>> records = scanRecordsFor(bagTag, 3);
    Map<String, ScanReceivedPayload> distinct = new LinkedHashMap<>();
    Map<String, Integer> copies = new LinkedHashMap<>();
    for (ConsumerRecord<String, String> record : records) {
      var event = EventJson.read(record.value(), ScanReceivedPayload.class);
      distinct.putIfAbsent(event.eventId(), event.payload());
      copies.merge(event.eventId(), 1, Integer::sum);
    }
    assertThat(distinct).hasSize(3);
    assertThat(copies.values()).allSatisfy(n -> assertThat(n).isBetween(1, 2));
    assertThat(distinct.values().stream().map(ScanReceivedPayload::deviceSequence).toList())
        .containsExactly(1L, 2L, 3L);
  }
}
