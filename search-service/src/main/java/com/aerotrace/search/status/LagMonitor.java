package com.aerotrace.search.status;

import com.aerotrace.search.config.SearchProperties;
import com.aerotrace.search.constant.MetricNames;
import com.aerotrace.search.constant.SearchTopics;
import com.aerotrace.search.indexer.IndexMetrics;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.ListOffsetsResult;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Measures how far the indexes are behind the topics (ADR 0004, decision 9):
 *
 * <ul>
 *   <li>{@code search.index.lag.events}: events of the consumed topics the consumer group has not
 *       committed (committed only after Elasticsearch acknowledged them);
 *   <li>{@code search.index.lag}: 0 when that is 0, otherwise the age ({@code now - publishedAt})
 *       of the newest event acknowledged. A plain "now minus the last event time" would grow on an
 *       idle topic, so being caught up is decided by the group lag.
 * </ul>
 *
 * {@code indexedThrough} is the watermark the reports carry: when caught up, the moment of the
 * check; otherwise the newest acknowledged {@code publishedAt}. The newest acknowledged event is
 * known per instance; with several instances each reports its own partitions' newest event.
 */
@Component
public class LagMonitor implements DisposableBean {

  private static final Logger LOG = LoggerFactory.getLogger(LagMonitor.class);
  private static final long TIMEOUT_SECONDS = 3;

  /**
   * One measurement. {@code lagEvents} and {@code lagSeconds} are null when Kafka did not answer.
   */
  public record Lag(Long lagEvents, Double lagSeconds, Instant indexedThrough, Instant checkedAt) {}

  private final KafkaAdmin kafkaAdmin;
  private final IndexMetrics metrics;
  private final Clock clock;
  private final String groupId;
  private volatile AdminClient admin;
  private volatile Lag last;

  public LagMonitor(
      KafkaAdmin kafkaAdmin,
      IndexMetrics metrics,
      MeterRegistry registry,
      SearchProperties properties,
      Clock clock) {
    this.kafkaAdmin = kafkaAdmin;
    this.metrics = metrics;
    this.clock = clock;
    this.groupId = properties.consumers().groupId();
    this.last = new Lag(null, null, null, null);
    Gauge.builder(MetricNames.INDEX_LAG, this, m -> value(m.last.lagSeconds()))
        .description("Seconds the search indexes are behind the topics; 0 when caught up")
        .baseUnit("seconds")
        .register(registry);
    Gauge.builder(MetricNames.INDEX_LAG_EVENTS, this, m -> value(m.last.lagEvents()))
        .description("Events of the consumed topics not yet indexed and committed")
        .register(registry);
  }

  private static double value(Number number) {
    return number == null ? Double.NaN : number.doubleValue();
  }

  @Scheduled(
      fixedDelayString = "${aerotrace.search.lag.check-interval:15s}",
      initialDelayString = "${aerotrace.search.lag.check-interval:15s}")
  void scheduledCheck() {
    check();
  }

  /** Measures now; the last good measurement is kept when Kafka does not answer. */
  public Lag check() {
    Instant now = clock.instant();
    try {
      long events = uncommittedEvents();
      Instant newest = metrics.newestIndexed();
      Instant through = events == 0 ? now : newest;
      last = new Lag(events, lagSeconds(events, newest, now), through, now);
    } catch (Exception e) {
      if (e instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      LOG.warn("Indexing lag not measured: {}", e.getClass().getSimpleName());
      last = new Lag(null, null, last.indexedThrough(), now);
    }
    return last;
  }

  public Lag last() {
    return last;
  }

  /** 0 when caught up; the age of the newest acknowledged event otherwise; null when unknown. */
  static Double lagSeconds(long lagEvents, Instant newestIndexed, Instant now) {
    if (lagEvents == 0) {
      return 0.0;
    }
    if (newestIndexed == null) {
      return null;
    }
    Duration age = Duration.between(newestIndexed, now);
    return age.isNegative() ? 0.0 : age.toMillis() / 1000.0;
  }

  private AdminClient admin() {
    AdminClient current = admin;
    if (current == null) {
      synchronized (this) {
        if (admin == null) {
          admin = AdminClient.create(kafkaAdmin.getConfigurationProperties());
        }
        current = admin;
      }
    }
    return current;
  }

  private long uncommittedEvents() throws Exception {
    AdminClient client = admin();
    Set<String> existing = client.listTopics().names().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    Map<TopicPartition, OffsetSpec> latest = new HashMap<>();
    Map<TopicPartition, OffsetSpec> earliest = new HashMap<>();
    Map<String, TopicDescription> topics =
        client
            .describeTopics(SearchTopics.CONSUMED.stream().filter(existing::contains).toList())
            .allTopicNames()
            .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    for (TopicDescription topic : topics.values()) {
      topic
          .partitions()
          .forEach(
              p -> {
                TopicPartition tp = new TopicPartition(topic.name(), p.partition());
                latest.put(tp, OffsetSpec.latest());
                earliest.put(tp, OffsetSpec.earliest());
              });
    }
    if (latest.isEmpty()) {
      return 0;
    }
    Map<TopicPartition, ListOffsetsResult.ListOffsetsResultInfo> ends =
        client.listOffsets(latest).all().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    Map<TopicPartition, ListOffsetsResult.ListOffsetsResultInfo> starts =
        client.listOffsets(earliest).all().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    Map<TopicPartition, OffsetAndMetadata> committed =
        client
            .listConsumerGroupOffsets(groupId)
            .partitionsToOffsetAndMetadata()
            .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    long lag = 0;
    for (TopicPartition tp : latest.keySet()) {
      long end = ends.get(tp).offset();
      OffsetAndMetadata done = committed.get(tp);
      long from = done != null ? done.offset() : starts.get(tp).offset();
      lag += Math.max(0, end - from);
    }
    return lag;
  }

  @Override
  public void destroy() {
    AdminClient current = admin;
    if (current != null) {
      current.close(Duration.ofSeconds(2));
    }
  }
}
