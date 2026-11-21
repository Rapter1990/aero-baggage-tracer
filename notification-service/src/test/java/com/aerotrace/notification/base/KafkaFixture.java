package com.aerotrace.notification.base;

import com.aerotrace.common.event.Topics;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Assumptions;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.kafka.KafkaContainer;

/**
 * A real Kafka broker with {@code recovery.case.changed.v1} and {@code baggage.dead-letter.v1}
 * (one partition each), plus the producer and reader helpers the tests need. {@link #shared()} is
 * one broker for the whole JVM; {@link #dedicated()} is a broker a test may pause.
 */
public final class KafkaFixture {

  private static KafkaFixture shared;

  private final KafkaContainer kafka;

  private KafkaFixture() throws Exception {
    kafka = new KafkaContainer("apache/kafka:3.9.1");
    kafka.start();
    Properties admin = new Properties();
    admin.put("bootstrap.servers", kafka.getBootstrapServers());
    try (AdminClient client = AdminClient.create(admin)) {
      client
          .createTopics(
              List.of(
                  new NewTopic(Topics.CASE_CHANGED, 1, (short) 1),
                  new NewTopic(Topics.DEAD_LETTER, 1, (short) 1)))
          .all()
          .get();
    }
  }

  public static synchronized KafkaFixture shared() {
    if (shared == null) {
      shared = create();
      KafkaFixture started = shared;
      Runtime.getRuntime().addShutdownHook(new Thread(started.kafka::stop));
    }
    return shared;
  }

  public static KafkaFixture dedicated() {
    return create();
  }

  private static KafkaFixture create() {
    Assumptions.assumeTrue(
        DockerClientFactory.instance().isDockerAvailable(), "Docker is not available");
    try {
      return new KafkaFixture();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  public String bootstrapServers() {
    return kafka.getBootstrapServers();
  }

  /** Stops the broker answering, as when it is gone from the network. */
  public void pause() {
    DockerClientFactory.instance().client().pauseContainerCmd(kafka.getContainerId()).exec();
  }

  public void resume() {
    try {
      DockerClientFactory.instance().client().unpauseContainerCmd(kafka.getContainerId()).exec();
    } catch (RuntimeException notPaused) {
      // Already running.
    }
  }

  public void stop() {
    kafka.stop();
  }

  public void publish(String topic, String key, String value) throws Exception {
    Properties config = new Properties();
    config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers());
    config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
    config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
    try (KafkaProducer<String, String> producer = new KafkaProducer<>(config)) {
      producer.send(new ProducerRecord<>(topic, key, value)).get();
    }
  }

  /** Rewinds a consumer group (which must have no running member) to the start of the topic. */
  public void rewind(String groupId, String topic) throws Exception {
    Properties admin = new Properties();
    admin.put("bootstrap.servers", bootstrapServers());
    try (AdminClient client = AdminClient.create(admin)) {
      client
          .alterConsumerGroupOffsets(
              groupId, Map.of(new TopicPartition(topic, 0), new OffsetAndMetadata(0L)))
          .all()
          .get();
    }
  }

  /**
   * Reads {@code topic} from the start and returns the records with {@code key}. It stops once
   * {@code expected} records were seen and a quiet period passed with no more (a duplicate would
   * show up in that period), or after {@code giveUp}, so a missing record fails the test.
   */
  public List<ConsumerRecord<String, String>> readByKey(
      String topic, String key, int expected, Duration quiet, Duration giveUp) {
    Properties config = new Properties();
    config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers());
    config.put(ConsumerConfig.GROUP_ID_CONFIG, "test-reader-" + UUID.randomUUID());
    config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
    config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
    config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
    List<ConsumerRecord<String, String>> found = new ArrayList<>();
    try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(config)) {
      consumer.subscribe(List.of(topic));
      long deadline = System.nanoTime() + giveUp.toNanos();
      long quietUntil = 0;
      while (System.nanoTime() < deadline) {
        for (ConsumerRecord<String, String> r : consumer.poll(Duration.ofMillis(500))) {
          if (key.equals(r.key())) {
            found.add(r);
          }
        }
        if (found.size() >= expected) {
          if (quietUntil == 0) {
            quietUntil = System.nanoTime() + quiet.toNanos();
          } else if (System.nanoTime() > quietUntil) {
            break;
          }
        }
      }
    }
    return found;
  }
}
