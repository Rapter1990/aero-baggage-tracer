package com.aerotrace.search.indexer;

import com.aerotrace.common.event.Topics;
import com.aerotrace.search.constant.SearchTopics;
import java.io.IOException;
import java.util.List;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * One batch listener per consumed topic. The offsets of a batch are committed by the container
 * after the method returned, that is after Elasticsearch acknowledged the bulk (ack mode {@code
 * BATCH}); a thrown exception commits nothing and the batch is retried (see {@link
 * KafkaConsumerConfiguration}). All four share one consumer group (whose lag is measured); each
 * subscribes to its own topic. Payloads are never logged.
 */
@Component
class IndexerListeners {

  private final EventIndexer indexer;

  IndexerListeners(EventIndexer indexer) {
    this.indexer = indexer;
  }

  @KafkaListener(
      id = "search-case-changed",
      topics = Topics.CASE_CHANGED,
      groupId = "${aerotrace.search.consumers.group-id}",
      autoStartup = "${aerotrace.search.consumers.enabled:true}")
  void onCases(List<ConsumerRecord<String, String>> records) throws IOException {
    indexer.indexCases(records);
  }

  @KafkaListener(
      id = "search-scan-received",
      topics = Topics.SCAN_RECEIVED,
      groupId = "${aerotrace.search.consumers.group-id}",
      autoStartup = "${aerotrace.search.consumers.enabled:true}")
  void onScans(List<ConsumerRecord<String, String>> records) throws IOException {
    indexer.indexScans(records);
  }

  @KafkaListener(
      id = "search-risk-changed",
      topics = Topics.RISK_CHANGED,
      groupId = "${aerotrace.search.consumers.group-id}",
      autoStartup = "${aerotrace.search.consumers.enabled:true}")
  void onRisks(List<ConsumerRecord<String, String>> records) throws IOException {
    indexer.indexRisks(records);
  }

  @KafkaListener(
      id = "search-bag-registered",
      topics = SearchTopics.BAG_REGISTERED,
      groupId = "${aerotrace.search.consumers.group-id}",
      autoStartup = "${aerotrace.search.consumers.enabled:true}")
  void onRegistrations(List<ConsumerRecord<String, String>> records) throws IOException {
    indexer.indexRegistrations(records);
  }
}
