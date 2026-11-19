package com.aerotrace.search.reindex;

import com.aerotrace.common.event.EventEnvelope;
import com.aerotrace.common.event.EventJson;
import com.aerotrace.common.event.RiskChangedPayload;
import com.aerotrace.common.event.Topics;
import com.aerotrace.search.constant.SearchTopics;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.stereotype.Component;

/**
 * Replays {@code baggage.risk.changed.v1} from offset 0 for a rebuild: risk level has no database
 * owner (risk-streams keeps it in a KTable), so the topic is its source (ADR 0004, decision 1; #15
 * makes it compacted with a long retention). It reads with no consumer group and commits nothing,
 * so the live indexer's offsets and the topic are untouched; it stops at the end offsets taken when
 * it started. Unreadable events are skipped (the live indexer dead-letters them).
 *
 * <p>Accepted limit: risk history older than the topic's retention cannot be rebuilt.
 */
@Component
public class RiskTopicReplay {

  /** Handles one polled batch of risk events. */
  @FunctionalInterface
  public interface BatchHandler {
    void handle(List<EventEnvelope<RiskChangedPayload>> events) throws IOException;
  }

  private static final Logger LOG = LoggerFactory.getLogger(RiskTopicReplay.class);
  private static final Duration POLL = Duration.ofMillis(500);

  private final KafkaProperties kafka;

  public RiskTopicReplay(KafkaProperties kafka) {
    this.kafka = kafka;
  }

  /** Returns the number of events handed to the handler. */
  public long replay(BatchHandler handler) throws IOException {
    Map<String, Object> config = kafka.buildConsumerProperties();
    config.remove(ConsumerConfig.GROUP_ID_CONFIG);
    config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
    config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
    config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
    long count = 0;
    try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(config)) {
      List<TopicPartition> partitions = new ArrayList<>();
      List<PartitionInfo> infos = consumer.partitionsFor(Topics.RISK_CHANGED);
      if (infos == null) {
        return 0;
      }
      for (PartitionInfo info : infos) {
        partitions.add(new TopicPartition(info.topic(), info.partition()));
      }
      consumer.assign(partitions);
      consumer.seekToBeginning(partitions);
      Map<TopicPartition, Long> end = consumer.endOffsets(partitions);
      while (!caughtUp(consumer, end)) {
        List<EventEnvelope<RiskChangedPayload>> batch = new ArrayList<>();
        for (ConsumerRecord<String, String> record : consumer.poll(POLL)) {
          if (record.offset()
              >= end.getOrDefault(new TopicPartition(record.topic(), record.partition()), 0L)) {
            continue;
          }
          EventEnvelope<RiskChangedPayload> event = read(record.value());
          if (event != null) {
            batch.add(event);
          }
        }
        if (!batch.isEmpty()) {
          handler.handle(batch);
          count += batch.size();
        }
      }
    }
    LOG.info("Replayed {} risk events", count);
    return count;
  }

  private static boolean caughtUp(KafkaConsumer<?, ?> consumer, Map<TopicPartition, Long> end) {
    for (Map.Entry<TopicPartition, Long> partition : end.entrySet()) {
      if (consumer.position(partition.getKey()) < partition.getValue()) {
        return false;
      }
    }
    return true;
  }

  private static EventEnvelope<RiskChangedPayload> read(String json) {
    try {
      if (json == null
          || !SearchTopics.EVENT_TYPE_RISK_CHANGED.equals(EventJson.readHeader(json).eventType())) {
        return null;
      }
      EventEnvelope<RiskChangedPayload> event = EventJson.read(json, RiskChangedPayload.class);
      RiskChangedPayload r = event.payload();
      boolean complete =
          r.bagTag() != null && !r.bagTag().isBlank() && r.legId() != null && r.current() != null;
      return complete ? event : null;
    } catch (RuntimeException unreadable) {
      return null;
    }
  }
}
