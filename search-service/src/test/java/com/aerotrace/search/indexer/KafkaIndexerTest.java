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
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

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

  private static void publish(String topic, String key, String value) throws Exception {
    Properties config = new Properties();
    config.put("bootstrap.servers", kafkaBootstrapServers());
    config.put("key.serializer", StringSerializer.class.getName());
    config.put("value.serializer", StringSerializer.class.getName());
    try (KafkaProducer<String, String> producer = new KafkaProducer<>(config)) {
      producer.send(new ProducerRecord<>(topic, key, value)).get();
    }
  }

  /** Events of the topic not yet committed by the indexer's group. */
  private static long uncommitted(String topic) throws Exception {
    Properties config = new Properties();
    config.put("bootstrap.servers", kafkaBootstrapServers());
    try (AdminClient admin = AdminClient.create(config)) {
      Map<TopicPartition, OffsetAndMetadata> committed =
          admin.listConsumerGroupOffsets(GROUP).partitionsToOffsetAndMetadata().get();
      long lag = 0;
      for (int partition = 0; partition < 2; partition++) {
        TopicPartition tp = new TopicPartition(topic, partition);
        long end =
            admin.listOffsets(Map.of(tp, OffsetSpec.latest())).partitionResult(tp).get().offset();
        OffsetAndMetadata done = committed.get(tp);
        lag += end - (done == null ? 0 : done.offset());
      }
      return lag;
    }
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
      "while Elasticsearch is down nothing is committed or dead-lettered; when it is"
          + " back the event is indexed")
  void outageLosesNothing() throws Exception {
    // Let the listeners reach a steady, committed state first.
    await().atMost(Duration.ofSeconds(60)).until(() -> uncommitted(Topics.CASE_CHANGED) == 0);
    String caseId = newId("case");
    pauseElasticsearch();
    try {
      publish(
          Topics.CASE_CHANGED,
          caseId,
          Events.caseV1(caseId, newBagTag(), newId("leg"), RecoveryState.OPEN, 1));
      Thread.sleep(8_000);
      assertThat(uncommitted(Topics.CASE_CHANGED)).isEqualTo(1);
    } finally {
      resumeElasticsearch();
    }

    await().atMost(Duration.ofSeconds(90)).until(() -> indexed(caseId));
    await().atMost(Duration.ofSeconds(30)).until(() -> uncommitted(Topics.CASE_CHANGED) == 0);
    assertThat(List.of(caseId)).allSatisfy(id -> assertThat(indexed(id)).isTrue());
  }
}
