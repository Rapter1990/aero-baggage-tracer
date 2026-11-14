package com.aerotrace.journey.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.aerotrace.common.event.EventEnvelope;
import com.aerotrace.common.event.EventJson;
import com.aerotrace.common.event.ScanReceivedPayload;
import com.aerotrace.common.event.Topics;
import com.aerotrace.common.security.Role;
import com.aerotrace.journey.base.AbstractApiTest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.kafka.KafkaContainer;

/**
 * The relay against a real Kafka: events leave the outbox keyed by bag tag, are marked published,
 * and two relays running at the same time (two replicas) publish every event exactly once.
 */
class OutboxRelayKafkaTest extends AbstractApiTest {

  private static KafkaContainer kafka;

  @BeforeAll
  static void startKafka() {
    org.junit.jupiter.api.Assumptions.assumeTrue(
        DockerClientFactory.instance().isDockerAvailable(), "Docker is not available");
    synchronized (OutboxRelayKafkaTest.class) {
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
    registry.add("spring.kafka.producer.properties.max.block.ms", () -> "10000");
    registry.add("spring.kafka.producer.properties.request.timeout.ms", () -> "10000");
    registry.add("spring.kafka.producer.properties.delivery.timeout.ms", () -> "20000");
  }

  @Autowired private OutboxRelay relay;

  private List<ConsumerRecord<String, String>> consumeFor(Set<String> bagTags, int expected) {
    Properties config = new Properties();
    config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
    config.put(ConsumerConfig.GROUP_ID_CONFIG, "test-" + java.util.UUID.randomUUID());
    config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
    config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
    config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
    List<ConsumerRecord<String, String>> found = new ArrayList<>();
    try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(config)) {
      consumer.subscribe(List.of(Topics.SCAN_RECEIVED));
      long deadline = System.currentTimeMillis() + 30_000;
      while (found.size() < expected && System.currentTimeMillis() < deadline) {
        for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(500))) {
          if (bagTags.contains(record.key())) {
            found.add(record);
          }
        }
      }
      // A little longer, to catch a duplicate that would arrive after the expected count.
      long grace = System.currentTimeMillis() + 2_000;
      while (System.currentTimeMillis() < grace) {
        for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(300))) {
          if (bagTags.contains(record.key())) {
            found.add(record);
          }
        }
      }
    }
    return found;
  }

  private String bagWithScans(int scans) throws Exception {
    String bagTag = registeredBag(newPassengerRef());
    for (int i = 1; i <= scans; i++) {
      Map<String, Object> body = barcodeScan(bagTag, "IST", "CHECK_IN", i, minutesAgo(5 - i));
      scan(body, newKey(), as(Role.AGENT)).andReturn();
    }
    return bagTag;
  }

  @Test
  void pendingEventsArePublishedKeyedByBagTagAndMarkedPublished() throws Exception {
    // Whatever other tests left in the outbox goes first.
    while (relay.publishPending() > 0) {}
    String bagTag = bagWithScans(3);

    int sent = relay.publishPending();

    assertThat(sent).isGreaterThanOrEqualTo(3);
    assertThat(
            count(
                "SELECT count(*) FROM journey.outbox_event WHERE message_key = ?"
                    + " AND published_at IS NOT NULL",
                bagTag))
        .isEqualTo(3);
    List<ConsumerRecord<String, String>> records = consumeFor(Set.of(bagTag), 3);
    assertThat(records).hasSize(3);
    assertThat(records).allSatisfy(r -> assertThat(r.key()).isEqualTo(bagTag));
    EventEnvelope<ScanReceivedPayload> first =
        EventJson.read(records.get(0).value(), ScanReceivedPayload.class);
    assertThat(first.eventType()).isEqualTo("baggage.scan.received");
    assertThat(first.payload().bagTag()).isEqualTo(bagTag);
    // Nothing left to send for this bag.
    assertThat(relay.publishPending()).isZero();
  }

  @Test
  void twoReplicasRelayingAtOncePublishEveryEventExactlyOnce() throws Exception {
    while (relay.publishPending() > 0) {}
    Set<String> bags = new HashSet<>();
    for (int i = 0; i < 4; i++) {
      bags.add(bagWithScans(3));
    }

    List<Callable<Integer>> replicas = new ArrayList<>();
    for (int i = 0; i < 3; i++) {
      replicas.add(
          () -> {
            int total = 0;
            int sent;
            do {
              sent = relay.publishPending();
              total += sent;
            } while (sent > 0);
            return total;
          });
    }
    int published = 0;
    try (ExecutorService pool = Executors.newFixedThreadPool(replicas.size())) {
      for (Future<Integer> result : pool.invokeAll(replicas)) {
        published += result.get();
      }
    }

    assertThat(published).isEqualTo(12);
    List<ConsumerRecord<String, String>> records = consumeFor(bags, 12);
    Set<String> eventIds = new HashSet<>();
    for (ConsumerRecord<String, String> record : records) {
      eventIds.add(EventJson.read(record.value(), ScanReceivedPayload.class).eventId());
    }
    assertThat(records).hasSize(12);
    assertThat(eventIds).hasSize(12);
  }

  @Test
  void anEventStaysPendingWhileTheBrokerIsUnreachable() throws Exception {
    // A relay pointed at a broker that does not exist must not mark anything published.
    while (relay.publishPending() > 0) {}
    String bagTag = bagWithScans(1);
    var unreachable =
        new org.springframework.kafka.core.KafkaTemplate<String, String>(
            new org.springframework.kafka.core.DefaultKafkaProducerFactory<>(
                Map.of(
                    "bootstrap.servers",
                    "localhost:1",
                    "key.serializer",
                    org.apache.kafka.common.serialization.StringSerializer.class,
                    "value.serializer",
                    org.apache.kafka.common.serialization.StringSerializer.class,
                    "max.block.ms",
                    500,
                    "delivery.timeout.ms",
                    1500,
                    "request.timeout.ms",
                    500)));
    OutboxRelay brokenRelay =
        new OutboxRelay(outbox, unreachable, properties, clock, meters, transactionManager);

    assertThat(brokenRelay.publishPending()).isZero();

    assertThat(
            count(
                "SELECT count(*) FROM journey.outbox_event WHERE message_key = ?"
                    + " AND published_at IS NULL AND attempts >= 1",
                bagTag))
        .isEqualTo(1);
    // The healthy relay then delivers it.
    assertThat(relay.publishPending()).isGreaterThanOrEqualTo(1);
  }

  @Autowired private com.aerotrace.journey.repository.OutboxEventRepository outbox;
  @Autowired private com.aerotrace.journey.config.JourneyProperties properties;
  @Autowired private java.time.Clock clock;
  @Autowired private io.micrometer.core.instrument.MeterRegistry meters;

  @Autowired private org.springframework.transaction.PlatformTransactionManager transactionManager;
}
