package com.aerotrace.search.indexer;

import static org.assertj.core.api.Assertions.assertThat;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.aerotrace.search.base.AbstractSearchTest;
import com.aerotrace.search.base.Events;
import com.aerotrace.search.constant.SearchTopics;
import com.aerotrace.search.elastic.IndexFamily;
import com.aerotrace.search.elastic.SupportIndex;
import com.aerotrace.search.indexer.ReadModelWriter.ItemResult;
import com.aerotrace.search.indexer.ReadModelWriter.RiskHour;
import com.aerotrace.search.indexer.model.LegSnapshot;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Items Elasticsearch refuses for good are results, never exceptions: an exception would make the
 * listener retry the batch forever (the retry policy has no limit and no dead-letter step).
 */
@SpringBootTest
class RefusedItemsTest extends AbstractSearchTest {

  @Autowired ReadModelWriter writer;
  @Autowired EventIndexer indexer;
  @Autowired ElasticsearchClient client;
  @Autowired MeterRegistry meters;

  private double failed(String topic) {
    var counter =
        meters.find("search.index.events").tags("outcome", "failed", "topic", topic).counter();
    return counter == null ? 0 : counter.count();
  }

  @Test
  @DisplayName("a leg Elasticsearch refuses is returned, and the other legs are still stored")
  void refusedLegIsReturned() throws Exception {
    String good = newId("leg");
    String bad = newId("leg");
    Map<String, LegSnapshot> legs = new LinkedHashMap<>();
    legs.put(good, new LegSnapshot(good, "TK1", "TK", "IST", "LHR", Instant.now()));
    // A keyword longer than Lucene's term limit (32766 bytes) is refused by Elasticsearch.
    legs.put(bad, new LegSnapshot(bad, "X".repeat(40_000), "TK", "IST", "LHR", Instant.now()));

    Map<String, String> refused = writer.writeLegs(legs, Map.of(good, "b", bad, "b"));

    assertThat(refused).containsOnlyKeys(bad);
    assertThat(client.exists(e -> e.index(SupportIndex.LEGS.index()).id(good)).value()).isTrue();
  }

  @Test
  @DisplayName("a risk hour Elasticsearch refuses is a rejected result, not an exception")
  void refusedRiskHourIsAResult() throws Exception {
    // A keyword longer than Lucene's term limit is refused for this item only (and the long id
    // is hashed, so the whole bulk request is not refused for it).
    List<ItemResult> results =
        writer.writeRiskHours(
            List.of(
                new RiskHour(newBagTag(), newId("leg"), Instant.now()),
                new RiskHour("B".repeat(40_000), newId("leg"), Instant.now())));

    assertThat(results).hasSize(2);
    assertThat(results.get(0).rejected()).isFalse();
    assertThat(results.get(1).rejected()).isTrue();
  }

  @Test
  @DisplayName(
      "a registration whose leg breaks the leg format is dead-lettered, the rest of the batch"
          + " is indexed")
  void malformedLegIsDeadLettered() throws Exception {
    String bag = newBagTag();
    String goodBag = newBagTag();
    double before = failed(SearchTopics.BAG_REGISTERED);
    LegSnapshot tooLong =
        new LegSnapshot(newId("leg"), "TK" + "1".repeat(5_000), "TK", "IST", "LHR", Instant.now());

    indexer.indexRegistrations(
        List.of(
            Events.record(
                SearchTopics.BAG_REGISTERED,
                bag,
                Events.registered(bag, List.of(tooLong), Instant.now())),
            Events.record(
                SearchTopics.BAG_REGISTERED,
                goodBag,
                Events.registered(
                    goodBag,
                    List.of(Events.leg(newId("leg"), "TK1981", "IST", "LHR")),
                    Instant.now()))));

    assertThat(failed(SearchTopics.BAG_REGISTERED)).isEqualTo(before + 1);
    assertThat(
            CaseIndexerTest.DeadLetters.read(
                kafkaBootstrapServers(), 1, SearchTopics.BAG_REGISTERED))
        .contains("flightNumber has an invalid format");
    assertThat(client.exists(e -> e.index(IndexFamily.JOURNEYS.readAlias()).id(goodBag)).value())
        .isTrue();
  }
}
