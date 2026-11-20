package com.aerotrace.search.indexer;

import static com.aerotrace.search.base.Events.caseRecord;
import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.GetResponse;
import com.aerotrace.common.correlation.CorrelationId;
import com.aerotrace.common.event.Topics;
import com.aerotrace.common.model.RecoveryState;
import com.aerotrace.search.base.AbstractSearchTest;
import com.aerotrace.search.base.Events;
import com.aerotrace.search.elastic.IndexFamily;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * The case indexer against a real Elasticsearch: external versions make it idempotent, a 409 is a
 * success, the leg lookup fills airport and airline whichever arrives first, and the envelope's
 * correlation id is restored while an event is handled.
 */
@SpringBootTest
class CaseIndexerTest extends AbstractSearchTest {

  @Autowired EventIndexer indexer;
  @Autowired ElasticsearchClient client;
  @Autowired MeterRegistry meters;

  @SuppressWarnings({"rawtypes", "unchecked"})
  private Map<String, Object> doc(String caseId) throws Exception {
    GetResponse<Map> get =
        client.get(g -> g.index(IndexFamily.CASES.readAlias()).id(caseId), Map.class);
    return get.found() ? get.source() : null;
  }

  private long version(String caseId) throws Exception {
    return client.get(g -> g.index(IndexFamily.CASES.readAlias()).id(caseId), Map.class).version();
  }

  private double outcome(Outcome outcome) {
    var counter =
        meters
            .find("search.index.events")
            .tags("outcome", outcome.tag(), "topic", Topics.CASE_CHANGED)
            .counter();
    return counter == null ? 0 : counter.count();
  }

  @Test
  @DisplayName("the same case event twice leaves one document at that version (a duplicate)")
  void sameEventTwice() throws Exception {
    String caseId = newId("case");
    String json = Events.caseV1(caseId, newBagTag(), newId("leg"), RecoveryState.OPEN, 3);
    double duplicates = outcome(Outcome.DUPLICATE);

    indexer.indexCases(List.of(caseRecord(json)));
    indexer.indexCases(List.of(caseRecord(json)));

    assertThat(version(caseId)).isEqualTo(3);
    assertThat(doc(caseId)).containsEntry("state", "OPEN").containsEntry("docId", caseId);
    assertThat(outcome(Outcome.DUPLICATE)).isEqualTo(duplicates + 1);
  }

  @Test
  @DisplayName("an older version after a newer one is stale and leaves the newest")
  void olderAfterNewer() throws Exception {
    String caseId = newId("case");
    String bag = newBagTag();
    String leg = newId("leg");
    double stale = outcome(Outcome.STALE);

    indexer.indexCases(
        List.of(caseRecord(Events.caseV1(caseId, bag, leg, RecoveryState.ASSESSING, 5))));
    indexer.indexCases(List.of(caseRecord(Events.caseV1(caseId, bag, leg, RecoveryState.OPEN, 4))));

    assertThat(version(caseId)).isEqualTo(5);
    assertThat(doc(caseId)).containsEntry("state", "ASSESSING");
    assertThat(outcome(Outcome.STALE)).isEqualTo(stale + 1);
  }

  @Test
  @DisplayName("versions out of order inside one batch end at the newest")
  void outOfOrderInOneBatch() throws Exception {
    String caseId = newId("case");
    String bag = newBagTag();
    String leg = newId("leg");

    indexer.indexCases(
        List.of(
            caseRecord(Events.caseV1(caseId, bag, leg, RecoveryState.RESERVED, 2)),
            caseRecord(Events.caseV1(caseId, bag, leg, RecoveryState.IN_TRANSIT, 3)),
            caseRecord(Events.caseV1(caseId, bag, leg, RecoveryState.ASSESSING, 1)),
            caseRecord(Events.caseV1(caseId, bag, leg, RecoveryState.OPEN, 0))));

    assertThat(version(caseId)).isEqualTo(3);
    assertThat(doc(caseId)).containsEntry("state", "IN_TRANSIT");
  }

  @Test
  @DisplayName(
      "a revision 2 event stores openedAt, resolvedAt, trigger and the resolution time, never the reason")
  void revisionTwoFields() throws Exception {
    String caseId = newId("case");
    Instant opened = Instant.parse("2026-03-01T10:00:00Z");
    Instant resolved = Instant.parse("2026-03-01T10:45:30Z");

    indexer.indexCases(
        List.of(
            caseRecord(
                Events.caseV2(
                    Events.change(
                        caseId,
                        newBagTag(),
                        newId("leg"),
                        RecoveryState.RESOLVED,
                        7,
                        opened,
                        resolved),
                    resolved,
                    "corr-v2"))));

    Map<String, Object> doc = doc(caseId);
    assertThat(doc)
        .containsEntry("trigger", "RISK")
        .containsEntry("openedAt", opened.toEpochMilli())
        .containsEntry("resolvedAt", resolved.toEpochMilli())
        .containsEntry("resolutionSeconds", 2730)
        .doesNotContainKey("assignedTo")
        // Free text that may name people or places is never indexed.
        .doesNotContainKey("reason");
  }

  @Test
  @DisplayName("a known leg gives the case its airport, airline and flight at once")
  void legKnownFirst() throws Exception {
    String bag = newBagTag();
    String leg = newId("leg");
    indexer.indexRegistrations(
        List.of(
            Events.record(
                Topics.BAG_REGISTERED,
                bag,
                Events.registered(
                    bag, List.of(Events.leg(leg, "TK1981", "IST", "LHR")), Instant.now()))));
    String caseId = newId("case");

    indexer.indexCases(List.of(caseRecord(Events.caseV1(caseId, bag, leg, RecoveryState.OPEN, 1))));

    assertThat(doc(caseId))
        .containsEntry("airport", "IST")
        .containsEntry("airline", "TK")
        .containsEntry("flightNumber", "TK1981");
  }

  @Test
  @DisplayName("a leg that arrives after the case completes it and keeps the case's version")
  void legKnownLater() throws Exception {
    String bag = newBagTag();
    String leg = newId("leg");
    String caseId = newId("case");
    indexer.indexCases(List.of(caseRecord(Events.caseV1(caseId, bag, leg, RecoveryState.OPEN, 4))));
    assertThat(doc(caseId)).doesNotContainKey("airport");

    indexer.indexRegistrations(
        List.of(
            Events.record(
                Topics.BAG_REGISTERED,
                bag,
                Events.registered(
                    bag, List.of(Events.leg(leg, "PC2001", "SAW", "ESB")), Instant.now()))));

    assertThat(doc(caseId)).containsEntry("airport", "SAW").containsEntry("airline", "PC");
    assertThat(version(caseId)).isEqualTo(4);
    // The next real event is still accepted.
    indexer.indexCases(
        List.of(caseRecord(Events.caseV1(caseId, bag, leg, RecoveryState.ASSESSING, 5))));
    assertThat(doc(caseId)).containsEntry("state", "ASSESSING").containsEntry("airport", "SAW");
  }

  @Test
  @DisplayName(
      "the envelope's correlation id is in the logging context while the event is"
          + " handled, and none is invented for an event without one")
  void correlationIdIsRestored() throws Exception {
    Logger logger = (Logger) LoggerFactory.getLogger(EventIndexer.class);
    Level before = logger.getLevel();
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    logger.setLevel(Level.DEBUG);
    try {
      String withId = newId("case");
      String withoutId = newId("case");
      indexer.indexCases(
          List.of(
              caseRecord(
                  Events.caseV2(
                      Events.change(
                          withId, newBagTag(), newId("leg"), RecoveryState.OPEN, 1, null, null),
                      Instant.now(),
                      "corr-restored-1")),
              caseRecord(
                  Events.caseV2(
                      Events.change(
                          withoutId, newBagTag(), newId("leg"), RecoveryState.OPEN, 1, null, null),
                      Instant.now(),
                      null))));

      Map<String, String> byCase = new java.util.HashMap<>();
      for (ILoggingEvent event : appender.list) {
        String mdc = event.getMDCPropertyMap().get(CorrelationId.MDC_KEY);
        byCase.put(event.getFormattedMessage().contains(withId) ? withId : withoutId, mdc);
      }
      assertThat(byCase.get(withId)).isEqualTo("corr-restored-1");
      assertThat(byCase).containsKey(withoutId);
      assertThat(byCase.get(withoutId)).isNull();
    } finally {
      logger.detachAppender(appender);
      logger.setLevel(before);
    }
  }

  @Test
  @DisplayName("a poison case event is dead-lettered and the rest of the batch is indexed")
  void poisonIsDeadLettered() throws Exception {
    String good = newId("case");
    String missingLeg = Events.caseV1(newId("case"), newBagTag(), "", RecoveryState.OPEN, 1);
    double failed = outcome(Outcome.FAILED);

    indexer.indexCases(
        List.of(
            caseRecord("not json"),
            caseRecord(missingLeg),
            caseRecord(Events.caseV1(good, newBagTag(), newId("leg"), RecoveryState.OPEN, 1))));

    assertThat(doc(good)).isNotNull();
    assertThat(outcome(Outcome.FAILED)).isEqualTo(failed + 2);
    assertThat(DeadLetters.read(kafkaBootstrapServers(), 2, Topics.CASE_CHANGED))
        .allSatisfy(letter -> assertThat(letter).doesNotContain("risk HIGH"));
  }

  /** Reads dead letters of one original topic. */
  static final class DeadLetters {

    static List<String> read(String servers, int atLeast, String originalTopic) {
      Properties config = new Properties();
      config.put("bootstrap.servers", servers);
      config.put("group.id", "dlt-" + java.util.UUID.randomUUID());
      config.put("auto.offset.reset", "earliest");
      config.put("key.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
      config.put("value.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
      List<String> found = new java.util.ArrayList<>();
      try (var consumer =
          new org.apache.kafka.clients.consumer.KafkaConsumer<String, String>(config)) {
        consumer.subscribe(List.of(Topics.DEAD_LETTER));
        long until = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < until && found.size() < atLeast) {
          for (var record : consumer.poll(java.time.Duration.ofMillis(500))) {
            if (record.value().contains("\"originalTopic\":\"" + originalTopic + "\"")
                && record.value().contains("\"reason\":\"")) {
              found.add(record.value());
            }
          }
        }
      }
      // The raw payload is kept, but the reason never quotes it.
      return found.stream()
          .map(
              value ->
                  com.aerotrace.common.event.EventJson.mapper()
                      .readTree(value)
                      .get("payload")
                      .get("reason")
                      .asString())
          .toList();
    }
  }
}
