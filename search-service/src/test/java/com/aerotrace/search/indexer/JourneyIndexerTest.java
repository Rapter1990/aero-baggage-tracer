package com.aerotrace.search.indexer;

import static org.assertj.core.api.Assertions.assertThat;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.aerotrace.common.event.Topics;
import com.aerotrace.common.model.RecoveryState;
import com.aerotrace.common.model.RiskLevel;
import com.aerotrace.search.base.AbstractSearchTest;
import com.aerotrace.search.base.Events;
import com.aerotrace.search.elastic.IndexFamily;
import com.aerotrace.search.elastic.SupportIndex;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * The journey document is written by four topics with unrelated clocks; each part is guarded by its
 * own clock, so any order of the same events gives the same document.
 */
@SpringBootTest
class JourneyIndexerTest extends AbstractSearchTest {

  private static final Instant T0 = Instant.parse("2026-05-01T08:00:00Z");

  @Autowired EventIndexer indexer;
  @Autowired ElasticsearchClient client;

  @SuppressWarnings({"rawtypes", "unchecked"})
  private Map<String, Object> journey(String bagTag) throws Exception {
    var get = client.get(g -> g.index(IndexFamily.JOURNEYS.readAlias()).id(bagTag), Map.class);
    return get.found() ? get.source() : null;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> leg(Map<String, Object> journey, String legId) {
    return ((List<Map<String, Object>>) journey.get("legs"))
        .stream().filter(l -> legId.equals(l.get("legId"))).findFirst().orElseThrow();
  }

  private static ConsumerRecord<String, String> scan(
      String bag, String airport, Instant at, long seq, String leg) {
    return Events.record(Topics.SCAN_RECEIVED, bag, Events.scan(bag, airport, at, seq, leg));
  }

  private static ConsumerRecord<String, String> risk(
      String bag, String leg, RiskLevel level, Instant at) {
    return Events.record(Topics.RISK_CHANGED, bag + ":" + leg, Events.risk(bag, leg, level, at));
  }

  @Test
  @DisplayName("the latest scan is replaced only by a newer (occurredAt, deviceSequence)")
  void scanGuard() throws Exception {
    String bag = newBagTag();
    String leg = newId("leg");

    indexer.indexScans(List.of(scan(bag, "IST", T0.plusSeconds(60), 5, leg)));
    indexer.indexScans(List.of(scan(bag, "SAW", T0, 9, leg))); // older
    indexer.indexScans(List.of(scan(bag, "ESB", T0.plusSeconds(60), 4, leg))); // same time, lower

    Map<String, Object> journey = journey(bag);
    @SuppressWarnings("unchecked")
    Map<String, Object> latest = (Map<String, Object>) journey.get("latestScan");
    assertThat(latest).containsEntry("airport", "IST").containsEntry("deviceSequence", 5);

    indexer.indexScans(List.of(scan(bag, "ADB", T0.plusSeconds(60), 6, leg))); // same time, higher
    @SuppressWarnings("unchecked")
    Map<String, Object> after = (Map<String, Object>) journey(bag).get("latestScan");
    assertThat(after).containsEntry("airport", "ADB");
    assertThat(leg(journey(bag), leg)).containsEntry("flightNumber", "TK1234");
    assertThat(journey(bag)).containsEntry("docId", bag);
  }

  @Test
  @DisplayName("a leg's risk is replaced only by a newer risk event; HIGH hours are recorded")
  void riskGuard() throws Exception {
    String bag = newBagTag();
    String leg = newId("leg");

    indexer.indexRisks(List.of(risk(bag, leg, RiskLevel.HIGH, T0.plusSeconds(120))));
    indexer.indexRisks(List.of(risk(bag, leg, RiskLevel.LOW, T0)));

    assertThat(leg(journey(bag), leg)).containsEntry("riskLevel", "HIGH");
    String hourId = bag + ":" + leg + ":" + T0.toEpochMilli();
    assertThat(
            client.get(g -> g.index(SupportIndex.RISK_HOURS.index()).id(hourId), Map.class).found())
        .isTrue();
  }

  @Test
  @DisplayName("a leg's case moves with the case's version and to a case opened later")
  void caseOnTheLeg() throws Exception {
    String bag = newBagTag();
    String leg = newId("leg");
    String first = newId("case");
    String second = newId("case");

    indexer.indexCases(
        List.of(
            Events.caseRecord(
                Events.caseV2(
                    Events.change(first, bag, leg, RecoveryState.CANCELLED, 3, T0, null),
                    T0.plusSeconds(600),
                    "c"))));
    indexer.indexCases(
        List.of(
            Events.caseRecord(
                Events.caseV2(
                    Events.change(first, bag, leg, RecoveryState.OPEN, 1, T0, null), T0, "c"))));
    assertThat(leg(journey(bag), leg)).containsEntry("caseState", "CANCELLED");

    indexer.indexCases(
        List.of(
            Events.caseRecord(
                Events.caseV2(
                    Events.change(
                        second, bag, leg, RecoveryState.OPEN, 1, T0.plusSeconds(900), null),
                    T0.plusSeconds(900),
                    "c"))));
    assertThat(leg(journey(bag), leg))
        .containsEntry("caseId", second)
        .containsEntry("caseState", "OPEN");
  }

  @Test
  @DisplayName("the itinerary fills origin, destination and airline of every leg")
  void itinerary() throws Exception {
    String bag = newBagTag();
    String leg1 = newId("leg");
    String leg2 = newId("leg");

    indexer.indexRegistrations(
        List.of(
            Events.record(
                Topics.BAG_REGISTERED,
                bag,
                Events.registered(
                    bag,
                    List.of(
                        Events.leg(leg1, "TK1", "ESB", "IST"),
                        Events.leg(leg2, "TK2", "IST", "JFK")),
                    T0))));

    assertThat(leg(journey(bag), leg2))
        .containsEntry("origin", "IST")
        .containsEntry("destination", "JFK")
        .containsEntry("airline", "TK");
  }

  @Test
  @DisplayName("the same events in another order, and replayed, give the same document")
  void orderDoesNotMatter() throws Exception {
    String bagA = newBagTag();
    String bagB = newBagTag();
    String leg = newId("leg");

    List<Runnable> forA = events(bagA, leg);
    List<Runnable> forB = events(bagB, leg);
    forA.forEach(Runnable::run);
    Collections.reverse(forB);
    forB.forEach(Runnable::run);
    forB.forEach(Runnable::run); // replay

    Map<String, Object> a = journey(bagA);
    Map<String, Object> b = journey(bagB);
    a.remove("docId");
    a.remove("bagTag");
    b.remove("docId");
    b.remove("bagTag");
    assertThat(b).isEqualTo(a);
  }

  private List<Runnable> events(String bag, String leg) {
    List<Runnable> steps = new ArrayList<>();
    steps.add(() -> run(() -> indexer.indexScans(List.of(scan(bag, "IST", T0, 1, leg)))));
    steps.add(
        () -> run(() -> indexer.indexScans(List.of(scan(bag, "IST", T0.plusSeconds(30), 2, leg)))));
    steps.add(
        () ->
            run(
                () ->
                    indexer.indexRisks(
                        List.of(risk(bag, leg, RiskLevel.HIGH, T0.plusSeconds(10))))));
    steps.add(() -> run(() -> indexer.indexRisks(List.of(risk(bag, leg, RiskLevel.MEDIUM, T0)))));
    return steps;
  }

  private interface Step {
    void run() throws Exception;
  }

  private static void run(Step step) {
    try {
      step.run();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
