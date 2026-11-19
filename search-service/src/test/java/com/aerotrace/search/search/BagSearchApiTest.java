package com.aerotrace.search.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.aerotrace.common.event.Topics;
import com.aerotrace.common.model.RecoveryState;
import com.aerotrace.common.model.RiskLevel;
import com.aerotrace.common.security.Role;
import com.aerotrace.search.base.AbstractSearchTest;
import com.aerotrace.search.base.Events;
import com.aerotrace.search.constant.SearchTopics;
import com.aerotrace.search.elastic.IndexFamily;
import com.aerotrace.search.indexer.EventIndexer;
import com.aerotrace.search.indexer.model.LegSnapshot;
import com.aerotrace.search.search.CursorCodec.Cursor;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code GET /api/v1/search/bags} over a fixed data set of its own (an airline and airports no
 * other test uses): one test per filter, a combined one, paging without duplicates or gaps, and
 * cursors that cannot be forged.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BagSearchApiTest extends AbstractSearchTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final Instant T0 = Instant.parse("2026-06-01T09:00:00Z");
  private static final int BAGS = 25;

  @Autowired MockMvc mockMvc;
  @Autowired EventIndexer indexer;
  @Autowired ElasticsearchClient client;
  @Autowired CursorCodec cursors;

  private final Random random = new Random();
  private String airline;
  private String origin;
  private String destination;
  private final List<String> bags = new ArrayList<>();

  private String letters(int n) {
    StringBuilder text = new StringBuilder();
    for (int i = 0; i < n; i++) {
      text.append((char) ('A' + random.nextInt(26)));
    }
    return text.toString();
  }

  /**
   * 25 bags of one airline from {@code origin} to {@code destination}. Bag i departs on flight
   * {@code <airline>10<i>}; bags 0-4 are HIGH risk, bags 5-7 have an OPEN case, bag 3 has both.
   * Several bags share an updatedAt, so the docId tie-break is exercised.
   */
  @BeforeAll
  void seed() throws Exception {
    airline = letters(1) + random.nextInt(10);
    origin = "Q" + letters(2);
    destination = "Z" + letters(2);
    for (int i = 0; i < BAGS; i++) {
      String bag = newBagTag();
      String leg = newId("leg");
      bags.add(bag);
      Instant at = T0.plusSeconds((i / 3) * 60L);
      indexer.indexRegistrations(
          List.of(
              Events.record(
                  SearchTopics.BAG_REGISTERED,
                  bag,
                  Events.registered(
                      bag,
                      List.of(
                          new LegSnapshot(
                              leg, airline + "10" + i, airline, origin, destination, T0)),
                      at))));
      if (i < 5) {
        indexer.indexRisks(
            List.of(
                Events.record(
                    Topics.RISK_CHANGED, bag, Events.risk(bag, leg, RiskLevel.HIGH, at))));
      }
      if (i == 3 || (i >= 5 && i < 8)) {
        indexer.indexCases(
            List.of(
                Events.caseRecord(Events.caseV1(newId("case"), bag, leg, RecoveryState.OPEN, 1))));
      }
    }
    client.indices().refresh(r -> r.index(IndexFamily.JOURNEYS.readAlias()));
  }

  private JsonNode search(String query) throws Exception {
    MvcResult result =
        mockMvc
            .perform(get("/api/v1/search/bags?" + query).with(as(Role.AGENT)))
            .andExpect(status().isOk())
            .andReturn();
    return JSON.readTree(result.getResponse().getContentAsString()).get("data");
  }

  private static Set<String> tags(JsonNode page) {
    Set<String> tags = new LinkedHashSet<>();
    page.get("items").forEach(item -> tags.add(item.get("bagTag").asString()));
    return tags;
  }

  @Test
  @DisplayName("filter by bag tag")
  void byBagTag() throws Exception {
    assertThat(tags(search("bagTag=" + bags.get(7)))).containsExactly(bags.get(7));
  }

  @Test
  @DisplayName("filter by airline")
  void byAirline() throws Exception {
    assertThat(tags(search("airline=" + airline + "&size=100")))
        .containsExactlyInAnyOrderElementsOf(bags);
  }

  @Test
  @DisplayName("filter by airport, matching a leg's origin or destination")
  void byAirport() throws Exception {
    assertThat(tags(search("airport=" + origin + "&size=100")))
        .containsExactlyInAnyOrderElementsOf(bags);
    assertThat(tags(search("airport=" + destination + "&size=100")))
        .containsExactlyInAnyOrderElementsOf(bags);
  }

  @Test
  @DisplayName("filter by flight")
  void byFlight() throws Exception {
    assertThat(tags(search("flight=" + airline + "1012"))).containsExactly(bags.get(12));
  }

  @Test
  @DisplayName("filter by risk level")
  void byRiskLevel() throws Exception {
    assertThat(tags(search("airline=" + airline + "&riskLevel=HIGH")))
        .containsExactlyInAnyOrderElementsOf(bags.subList(0, 5));
  }

  @Test
  @DisplayName("filter by case state")
  void byCaseState() throws Exception {
    assertThat(tags(search("airline=" + airline + "&caseState=OPEN")))
        .containsExactlyInAnyOrder(bags.get(3), bags.get(5), bags.get(6), bags.get(7));
  }

  @Test
  @DisplayName("filters combine with AND")
  void combined() throws Exception {
    assertThat(
            tags(
                search(
                    "airline="
                        + airline
                        + "&airport="
                        + origin
                        + "&riskLevel=HIGH&caseState=OPEN")))
        .containsExactly(bags.get(3));
    assertThat(tags(search("airline=" + airline + "&airport=XXX"))).isEmpty();
  }

  @Test
  @DisplayName(
      "paging with search_after over the fixed set has no duplicates and no gaps, in"
          + " updatedAt then docId order")
  void paging() throws Exception {
    List<String> seen = new ArrayList<>();
    List<Long> updated = new ArrayList<>();
    String cursor = null;
    int pages = 0;
    do {
      JsonNode page =
          search("airline=" + airline + "&size=7" + (cursor == null ? "" : "&cursor=" + cursor));
      page.get("items")
          .forEach(
              item -> {
                seen.add(item.get("bagTag").asString());
                updated.add(Instant.parse(item.get("updatedAt").asString()).toEpochMilli());
              });
      assertThat(page.get("size").asInt()).isEqualTo(7);
      cursor = page.get("hasMore").asBoolean() ? page.get("nextCursor").asString() : null;
      if (cursor == null) {
        assertThat(page.get("nextCursor").isNull()).isTrue();
      }
      pages++;
    } while (cursor != null);

    assertThat(pages).isEqualTo(4);
    assertThat(seen).hasSize(BAGS).doesNotHaveDuplicates();
    assertThat(seen).containsExactlyInAnyOrderElementsOf(bags);
    for (int i = 1; i < seen.size(); i++) {
      boolean ordered =
          updated.get(i - 1) > updated.get(i)
              || (updated.get(i - 1).equals(updated.get(i))
                  && seen.get(i - 1).compareTo(seen.get(i)) < 0);
      assertThat(ordered).as("order at %d", i).isTrue();
    }
  }

  @Test
  @DisplayName("items carry no raw Elasticsearch fields and no score")
  void noRawHits() throws Exception {
    JsonNode item = search("bagTag=" + bags.get(0)).get("items").get(0);

    assertThat(item.has("_score")).isFalse();
    assertThat(item.has("_index")).isFalse();
    assertThat(item.get("legs").get(0).get("riskLevel").asString()).isEqualTo("HIGH");
  }

  @Test
  @DisplayName("a forged or altered cursor is 400, and so is a cursor used with other filters")
  void forgedCursor() throws Exception {
    JsonNode first = search("airline=" + airline + "&size=5");
    String cursor = first.get("nextCursor").asString();
    String tampered =
        cursor.substring(0, cursor.length() - 2)
            + (cursor.charAt(cursor.length() - 2) == 'A' ? "B" : "A")
            + cursor.charAt(cursor.length() - 1);

    for (String bad : List.of(tampered, "not-a-cursor", "a.b.c", "....")) {
      mockMvc
          .perform(
              get("/api/v1/search/bags")
                  .param("airline", airline)
                  .param("size", "5")
                  .param("cursor", bad)
                  .with(as(Role.AGENT)))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }
    mockMvc
        .perform(
            get("/api/v1/search/bags")
                .param("airline", airline)
                .param("riskLevel", "HIGH")
                .param("cursor", cursor)
                .with(as(Role.AGENT)))
        .andExpect(status().isBadRequest());
  }

  @Test
  @DisplayName("an expired cursor, or one whose point-in-time is gone, is 410 CURSOR_EXPIRED")
  void expiredCursor() throws Exception {
    JsonNode first = search("airline=" + airline + "&size=5");
    Cursor live =
        cursors.decode(
            first.get("nextCursor").asString(),
            new BagSearchModels.Criteria(null, null, airline, null, null, null).fingerprint());
    String old =
        cursors.encode(
            new Cursor(live.pit(), live.after(), live.filters(), Instant.now().toEpochMilli() - 1));
    mockMvc
        .perform(
            get("/api/v1/search/bags")
                .param("airline", airline)
                .param("cursor", old)
                .with(as(Role.AGENT)))
        .andExpect(status().isGone())
        .andExpect(jsonPath("$.code").value("CURSOR_EXPIRED"));

    client.closePointInTime(c -> c.id(live.pit()));
    mockMvc
        .perform(
            get("/api/v1/search/bags")
                .param("airline", airline)
                .param("cursor", first.get("nextCursor").asString())
                .with(as(Role.AGENT)))
        .andExpect(status().isGone())
        .andExpect(jsonPath("$.code").value("CURSOR_EXPIRED"));
  }

  @Test
  @DisplayName("size 0, size 101 and unknown enum values are 400 VALIDATION_ERROR")
  void validation() throws Exception {
    for (String query :
        List.of(
            "size=0",
            "size=101",
            "riskLevel=EXTREME",
            "caseState=NOPE",
            "airport=istanbul",
            "bagTag=123",
            "flight=TK")) {
      mockMvc
          .perform(get("/api/v1/search/bags?" + query).with(as(Role.DISPATCHER)))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }
    mockMvc
        .perform(get("/api/v1/search/bags?size=100&airline=" + airline).with(as(Role.ADMIN)))
        .andExpect(status().isOk());
  }

  @Test
  @DisplayName("an Elasticsearch that does not answer is 503 SEARCH_UNAVAILABLE")
  void unavailable() throws Exception {
    pauseElasticsearch();
    try {
      mockMvc
          .perform(get("/api/v1/search/bags").with(as(Role.AGENT)))
          .andExpect(status().isServiceUnavailable())
          .andExpect(jsonPath("$.code").value("SEARCH_UNAVAILABLE"));
    } finally {
      resumeElasticsearch();
    }
  }
}
