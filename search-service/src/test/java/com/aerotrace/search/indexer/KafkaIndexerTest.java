package com.aerotrace.search.indexer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.aerotrace.common.event.Topics;
import com.aerotrace.common.model.RecoveryState;
import com.aerotrace.search.base.AbstractSearchTest;
import com.aerotrace.search.base.Events;
import com.aerotrace.search.elastic.IndexFamily;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.Future;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.GroupState;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * The listeners against a real Kafka and Elasticsearch: events become documents, a poison event is
 * dead-lettered without blocking the partition, and the offsets are committed only after
 * Elasticsearch acknowledged, so an outage loses nothing.
 */
@SpringBootTest
class KafkaIndexerTest extends AbstractSearchTest {

  private static final String GROUP = "search-test-" + UUID.randomUUID();

  @DynamicPropertySource
  static void consumers(DynamicPropertyRegistry registry) {
    registry.add("aerotrace.search.consumers.enabled", () -> "true");
    registry.add("aerotrace.search.consumers.group-id", () -> GROUP);
  }

  @Autowired ElasticsearchClient client;
  @MockitoSpyBean EventIndexer indexer;

  private static Properties producerConfig() {
    Properties config = new Properties();
    config.put("bootstrap.servers", kafkaBootstrapServers());
    config.put("key.serializer", StringSerializer.class.getName());
    config.put("value.serializer", StringSerializer.class.getName());
    return config;
  }

  private static void publish(String topic, String key, String value) throws Exception {
    try (KafkaProducer<String, String> producer = new KafkaProducer<>(producerConfig())) {
      producer.send(new ProducerRecord<>(topic, key, value)).get();
    }
  }

  /**
   * Publishes the records in one produce request (same key, so same partition, lingering until
   * flushed), so the listener reads them in one batch.
   */
  private static void publishTogether(String topic, String key, String... values) throws Exception {
    Properties config = producerConfig();
    config.put("linger.ms", "5000");
    try (KafkaProducer<String, String> producer = new KafkaProducer<>(config)) {
      List<Future<RecordMetadata>> sent = new ArrayList<>();
      for (String value : values) {
        sent.add(producer.send(new ProducerRecord<>(topic, key, value)));
      }
      producer.flush();
      for (Future<RecordMetadata> future : sent) {
        future.get();
      }
    }
  }

  /** Events of the topic not yet committed by the indexer's group, over all its partitions. */
  private static long uncommitted(String topic) throws Exception {
    Properties config = new Properties();
    config.put("bootstrap.servers", kafkaBootstrapServers());
    try (AdminClient admin = AdminClient.create(config)) {
      Map<TopicPartition, OffsetAndMetadata> committed =
          admin.listConsumerGroupOffsets(GROUP).partitionsToOffsetAndMetadata().get();
      Map<TopicPartition, OffsetSpec> latest = new HashMap<>();
      admin
          .describeTopics(List.of(topic))
          .allTopicNames()
          .get()
          .get(topic)
          .partitions()
          .forEach(p -> latest.put(new TopicPartition(topic, p.partition()), OffsetSpec.latest()));
      long lag = 0;
      for (var end : admin.listOffsets(latest).all().get().entrySet()) {
        OffsetAndMetadata done = committed.get(end.getKey());
        lag += end.getValue().offset() - (done == null ? 0 : done.offset());
      }
      return lag;
    }
  }

  /**
   * The group is stable with its four listeners. Until then a rebalance may hand a partition
   * without committed offsets to the listener again, which redelivers its records (at least once,
   * by design) and would blur what the test counts.
   */
  private static boolean groupStable() throws Exception {
    Properties config = new Properties();
    config.put("bootstrap.servers", kafkaBootstrapServers());
    try (AdminClient admin = AdminClient.create(config)) {
      var group = admin.describeConsumerGroups(List.of(GROUP)).all().get().get(GROUP);
      return group.groupState() == GroupState.STABLE && group.members().size() == 4;
    }
  }

  /** Dead letters with this key, read from the start of the topic for the given time. */
  private static long deadLettersWithKey(String key, Duration window) {
    Properties config = new Properties();
    config.put("bootstrap.servers", kafkaBootstrapServers());
    config.put("group.id", "dlt-" + UUID.randomUUID());
    config.put("auto.offset.reset", "earliest");
    config.put("key.deserializer", StringDeserializer.class.getName());
    config.put("value.deserializer", StringDeserializer.class.getName());
    long found = 0;
    try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(config)) {
      consumer.subscribe(List.of(Topics.DEAD_LETTER));
      long until = System.nanoTime() + window.toNanos();
      while (System.nanoTime() < until) {
        for (var record : consumer.poll(Duration.ofMillis(250))) {
          if (key.equals(record.key())) {
            found++;
          }
        }
      }
    }
    return found;
  }

  /** How often the indexer was handed a case batch that holds a record with this key. */
  @SuppressWarnings("unchecked")
  private long attemptsWith(String key) {
    return Mockito.mockingDetails(indexer).getInvocations().stream()
        .filter(call -> call.getMethod().getName().equals("indexCases"))
        .filter(
            call ->
                ((List<ConsumerRecord<String, String>>) call.getArgument(0))
                    .stream().anyMatch(r -> key.equals(r.key())))
        .count();
  }

  private boolean indexed(String caseId) throws Exception {
    return client.exists(e -> e.index(IndexFamily.CASES.readAlias()).id(caseId)).value();
  }

  @Test
  @DisplayName(
      "events become documents, a poison event does not block the partition, and the"
          + " offsets are committed")
  void eventsAreIndexedAndCommitted() throws Exception {
    String caseId = newId("case");
    String bag = newBagTag();
    publish(Topics.CASE_CHANGED, "poison", "{not json");
    publish(
        Topics.CASE_CHANGED,
        caseId,
        Events.caseV1(caseId, bag, newId("leg"), RecoveryState.OPEN, 1));

    await().atMost(Duration.ofSeconds(60)).until(() -> indexed(caseId));
    await().atMost(Duration.ofSeconds(30)).until(() -> uncommitted(Topics.CASE_CHANGED) == 0);
    assertThat(CaseIndexerTest.DeadLetters.read(kafkaBootstrapServers(), 1, Topics.CASE_CHANGED))
        .contains("event is not readable");
  }

  @Test
  @DisplayName(
      "while Elasticsearch is down nothing is committed or dead-lettered, also not the poison"
          + " event of the retried batch; when it is back the event is indexed and the poison"
          + " event is dead-lettered exactly once")
  void outageLosesNothingAndDeadLettersOnce() throws Exception {
    // Let the listeners reach a steady, committed state first.
    await().atMost(Duration.ofSeconds(60)).until(KafkaIndexerTest::groupStable);
    await().atMost(Duration.ofSeconds(60)).until(() -> uncommitted(Topics.CASE_CHANGED) == 0);
    String caseId = newId("case");
    String key = newId("outage");
    String poisonId = UUID.randomUUID().toString();
    String poison = "{\"eventId\":\"" + poisonId + "\",\"eventType\":\"not.a.case\"}";
    pauseElasticsearch();
    try {
      publishTogether(
          Topics.CASE_CHANGED,
          key,
          poison,
          Events.caseV1(caseId, newBagTag(), newId("leg"), RecoveryState.OPEN, 1));
      // The batch with both records failed and was retried at least once.
      await().atMost(Duration.ofSeconds(90)).until(() -> attemptsWith(key) >= 2);
      assertThat(uncommitted(Topics.CASE_CHANGED)).isEqualTo(2);
      assertThat(deadLettersWithKey(poisonId, Duration.ofSeconds(2))).isZero();
    } finally {
      resumeElasticsearch();
    }

    await().atMost(Duration.ofSeconds(90)).until(() -> indexed(caseId));
    await().atMost(Duration.ofSeconds(30)).until(() -> uncommitted(Topics.CASE_CHANGED) == 0);
    assertThat(deadLettersWithKey(poisonId, Duration.ofSeconds(5))).isEqualTo(1);
  }
}
