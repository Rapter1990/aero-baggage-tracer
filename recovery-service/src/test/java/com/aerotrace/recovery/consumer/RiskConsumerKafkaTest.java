package com.aerotrace.recovery.consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.aerotrace.common.event.DeadLetterPayload;
import com.aerotrace.common.event.EventEnvelope;
import com.aerotrace.common.event.EventJson;
import com.aerotrace.common.event.RiskChangedPayload;
import com.aerotrace.common.event.Topics;
import com.aerotrace.common.model.RecoveryState;
import com.aerotrace.common.model.RiskLevel;
import com.aerotrace.recovery.base.AbstractRecoveryTest;
import com.aerotrace.recovery.model.entity.RecoveryCaseEntity;
import com.aerotrace.recovery.repository.RecoveryCaseRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.kafka.KafkaContainer;

/**
 * The risk consumer against a real Kafka: events become cases, a redelivered event changes nothing,
 * and a poison event is dead-lettered without blocking the events behind it.
 */
@SpringBootTest
class RiskConsumerKafkaTest extends AbstractRecoveryTest {

  private static KafkaContainer kafka;

  @BeforeAll
  static void startKafka() throws Exception {
    org.junit.jupiter.api.Assumptions.assumeTrue(
        DockerClientFactory.instance().isDockerAvailable(), "Docker is not available");
    synchronized (RiskConsumerKafkaTest.class) {
      if (kafka == null) {
        kafka = new KafkaContainer("apache/kafka:3.9.1");
        kafka.start();
        Properties admin = new Properties();
        admin.put("bootstrap.servers", kafka.getBootstrapServers());
        try (AdminClient client = AdminClient.create(admin)) {
          client
              .createTopics(
                  List.of(
                      new NewTopic(Topics.RISK_CHANGED, 1, (short) 1),
                      new NewTopic(Topics.DEAD_LETTER, 1, (short) 1)))
              .all()
              .get();
        }
      }
    }
  }

  @DynamicPropertySource
  static void kafkaProperties(DynamicPropertyRegistry registry) {
    registry.add(
        "spring.kafka.bootstrap-servers",
        () -> {
          try {
            startKafka();
          } catch (Exception e) {
            throw new IllegalStateException(e);
          }
          return kafka.getBootstrapServers();
        });
    registry.add("aerotrace.recovery.consumers.enabled", () -> "true");
    registry.add("spring.kafka.consumer.group-id", () -> "recovery-test-" + UUID.randomUUID());
    registry.add("spring.kafka.producer.properties.max.block.ms", () -> "10000");
    registry.add("spring.kafka.producer.properties.request.timeout.ms", () -> "10000");
    registry.add("spring.kafka.producer.properties.delivery.timeout.ms", () -> "20000");
  }

  @Autowired RecoveryCaseRepository cases;

  // ------------------------------------------------------------------ helpers

  private static String bagTag() {
    return String.format("%010d", ThreadLocalRandom.current().nextLong(10_000_000_000L));
  }

  private static String highRisk(String eventId, String bagTag, String legId) {
    return EventJson.write(
        new EventEnvelope<>(
            eventId,
            "baggage.risk.changed",
            1,
            bagTag + ":" + legId,
            Instant.now(),
            Instant.now(),
            "corr-kafka",
            new RiskChangedPayload(
                bagTag, legId, RiskLevel.LOW, RiskLevel.HIGH, Instant.now().plusSeconds(900))));
  }

  private static void publish(String key, String value) throws Exception {
    Properties config = new Properties();
    config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
    config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
    config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
    try (KafkaProducer<String, String> producer = new KafkaProducer<>(config)) {
      producer.send(new ProducerRecord<>(Topics.RISK_CHANGED, key, value)).get();
    }
  }

  private List<RecoveryCaseEntity> casesOf(String bagTag) {
    return cases.findAll().stream().filter(c -> c.getBagTag().equals(bagTag)).toList();
  }

  private static List<ConsumerRecord<String, String>> deadLetters(int expected) {
    Properties config = new Properties();
    config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
    config.put(ConsumerConfig.GROUP_ID_CONFIG, "dlt-reader-" + UUID.randomUUID());
    config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
    config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
    config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
    List<ConsumerRecord<String, String>> found = new ArrayList<>();
    try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(config)) {
      consumer.subscribe(List.of(Topics.DEAD_LETTER));
      long deadline = System.currentTimeMillis() + 30_000;
      while (found.size() < expected && System.currentTimeMillis() < deadline) {
        consumer.poll(Duration.ofMillis(500)).forEach(found::add);
      }
    }
    return found;
  }

  // ------------------------------------------------------------------ tests

  @Test
  @DisplayName("AT-07 a HIGH risk event on Kafka opens a case, and its redelivery changes nothing")
  void riskEventOpensOneCaseEvenWhenDeliveredTwice() throws Exception {
    String bag = bagTag();
    String message = highRisk("EV-" + UUID.randomUUID(), bag, "LEG-2");

    publish(bag + ":LEG-2", message);
    publish(bag + ":LEG-2", message);

    await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(casesOf(bag)).hasSize(1));
    // Give the second delivery time to be handled, then check it created nothing.
    Thread.sleep(2_000);
    assertThat(casesOf(bag)).hasSize(1);
    assertThat(casesOf(bag).getFirst().getState()).isEqualTo(RecoveryState.OPEN);
  }

  @Test
  @DisplayName("Poison a bad event is dead-lettered and the events behind it still run")
  void poisonEventIsDeadLetteredAndDoesNotBlock() throws Exception {
    String bag = bagTag();
    String poisonId = "EV-POISON-" + UUID.randomUUID();
    String poison = highRisk(poisonId, "AB12", "LEG-2");

    publish("poison", poison);
    publish(bag + ":LEG-2", highRisk("EV-" + UUID.randomUUID(), bag, "LEG-2"));

    await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(casesOf(bag)).hasSize(1));
    List<ConsumerRecord<String, String>> dead = deadLetters(1);
    ConsumerRecord<String, String> record =
        dead.stream().filter(r -> poisonId.equals(r.key())).findFirst().orElseThrow();
    EventEnvelope<DeadLetterPayload> envelope =
        EventJson.read(record.value(), DeadLetterPayload.class);
    assertThat(envelope.eventType()).isEqualTo("baggage.dead-letter");
    assertThat(envelope.payload().originalTopic()).isEqualTo(Topics.RISK_CHANGED);
    assertThat(envelope.payload().originalEventId()).isEqualTo(poisonId);
    assertThat(envelope.payload().reason()).isEqualTo("bagTag is not a bag tag");
    assertThat(envelope.payload().rawPayload()).isEqualTo(poison);
  }

  @Test
  @DisplayName("Poison text that is not an event at all is dead-lettered by its position")
  void garbageIsDeadLetteredByPosition() throws Exception {
    publish("garbage", "garbage {");

    List<ConsumerRecord<String, String>> dead = deadLetters(1);

    ConsumerRecord<String, String> record =
        dead.stream()
            .filter(r -> r.key().startsWith(Topics.RISK_CHANGED + "-"))
            .findFirst()
            .orElseThrow();
    EventEnvelope<DeadLetterPayload> envelope =
        EventJson.read(record.value(), DeadLetterPayload.class);
    assertThat(envelope.payload().originalEventId()).isNull();
    assertThat(envelope.payload().reason()).isEqualTo("event is not readable");
  }
}
