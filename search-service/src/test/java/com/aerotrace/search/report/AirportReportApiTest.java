package com.aerotrace.search.report;

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
import com.aerotrace.search.elastic.SupportIndex;
import com.aerotrace.search.indexer.EventIndexer;
import com.aerotrace.search.indexer.model.LegSnapshot;
import java.time.Instant;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code GET /api/v1/reports/airport/{iata}} over a data set at an airport of its own, with the
 * definitions of ADR 0004 (decision 7).
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AirportReportApiTest extends AbstractSearchTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final Instant H0 = Instant.parse("2026-08-10T06:00:00Z");

  @Autowired MockMvc mockMvc;
  @Autowired EventIndexer indexer;
  @Autowired ElasticsearchClient client;

  private String airport;

  private String leg(String bag, String origin) throws Exception {
    String leg = newId("leg");
    indexer.indexRegistrations(
        List.of(
            Events.record(
                SearchTopics.BAG_REGISTERED,
                bag,
                Events.registered(
                    bag, List.of(new LegSnapshot(leg, "TK1", "TK", origin, "IST", H0)), H0))));
    return leg;
  }

  private void risk(String bag, String leg, RiskLevel level, Instant at) throws Exception {
    indexer.indexRisks(
        List.of(Events.record(Topics.RISK_CHANGED, bag, Events.risk(bag, leg, level, at))));
  }

  private void caseEvent(
      String caseId,
      String bag,
      String leg,
      RecoveryState state,
      long version,
      Instant opened,
      Instant resolved)
      throws Exception {
    indexer.indexCases(
        List.of(
            Events.caseRecord(
                Events.caseV2(
                    Events.change(caseId, bag, leg, state, version, opened, resolved),
                    resolved != null ? resolved : opened,
                    "corr-report"))));
  }

  /**
   * Hour 0: bag A HIGH twice (one bag), bag B HIGH; bag C MEDIUM (not risky). Case of A resolved in
   * hour 1 after 30 min; case of B resolved in hour 1 after 60 min; a CANCELLED case of C, then a
   * new case of C opened in hour 2 (a retry); a CLOSED_AS_UNRECOVERABLE case is not resolved. Hour
   * 3 is empty. The risk of a leg from another airport is not counted. A case whose leg became
   * known after it was indexed (the leg arrives later) is counted too.
   */
  @BeforeAll
  void seed() throws Exception {
    Random random = new Random();
    airport = "Y" + (char) ('A' + random.nextInt(26)) + (char) ('A' + random.nextInt(26));
    String a = newBagTag();
    String b = newBagTag();
    String c = newBagTag();
    String elsewhere = newBagTag();
    String legA = leg(a, airport);
    String legB = leg(b, airport);
    String legC = leg(c, airport);
    String legElsewhere = leg(elsewhere, "ZZZ");

    risk(a, legA, RiskLevel.HIGH, H0.plusSeconds(60));
    risk(a, legA, RiskLevel.HIGH, H0.plusSeconds(1200));
    risk(b, legB, RiskLevel.HIGH, H0.plusSeconds(300));
    risk(c, legC, RiskLevel.MEDIUM, H0.plusSeconds(300));
    risk(elsewhere, legElsewhere, RiskLevel.HIGH, H0.plusSeconds(300));

    caseEvent(
        newId("case"),
        a,
        legA,
        RecoveryState.RESOLVED,
        5,
        H0.plusSeconds(3000),
        H0.plusSeconds(4800));
    caseEvent(
        newId("case"),
        b,
        legB,
        RecoveryState.RESOLVED,
        5,
        H0.plusSeconds(1800),
        H0.plusSeconds(5400));
    caseEvent(newId("case"), c, legC, RecoveryState.CANCELLED, 3, H0.plusSeconds(600), null);
    caseEvent(newId("case"), c, legC, RecoveryState.OPEN, 1, H0.plusSeconds(7300), null);
    caseEvent(
        newId("case"),
        elsewhere,
        legElsewhere,
        RecoveryState.CLOSED_AS_UNRECOVERABLE,
        4,
        H0.plusSeconds(600),
        H0.plusSeconds(4000));

    // A case indexed before its leg is known, completed when the leg arrives.
    String late = newBagTag();
    String lateLeg = newId("leg");
    caseEvent(
        newId("case"),
        late,
        lateLeg,
        RecoveryState.RESOLVED,
        2,
        H0.plusSeconds(3700),
        H0.plusSeconds(3900));
    indexer.indexRegistrations(
        List.of(
            Events.record(
                SearchTopics.BAG_REGISTERED,
                late,
                Events.registered(
                    late,
                    List.of(new LegSnapshot(lateLeg, "TK2", "TK", airport, "IST", H0)),
                    H0))));

    client.indices().refresh(r -> r.index(IndexFamily.CASES.readAlias()));
    client.indices().refresh(r -> r.index(SupportIndex.RISK_HOURS.index()));
  }

  private JsonNode report(String iata, String from, String to) throws Exception {
    return JSON.readTree(
            mockMvc
                .perform(
                    get("/api/v1/reports/airport/{iata}", iata)
                        .param("from", from)
                        .param("to", to)
                        .with(as(Role.AGENT)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString())
        .get("data");
  }

  @Test
  @DisplayName(
      "hourly buckets with risky bags, resolved recoveries, retries and mean resolution"
          + " time as defined in ADR 0004, zero-filled, with null mean when nothing was resolved")
  void definitions() throws Exception {
    JsonNode report = report(airport, H0.toString(), H0.plusSeconds(4 * 3600).toString());
    JsonNode buckets = report.get("buckets");

    assertThat(buckets).hasSize(4);
    assertThat(buckets.get(0).get("hour").asString()).isEqualTo(H0.toString());
    // Hour 0: A (twice) and B are HIGH; C is MEDIUM; the other airport does not count.
    assertThat(buckets.get(0).get("riskyBags").asLong()).isEqualTo(2);
    assertThat(buckets.get(0).get("resolvedRecoveries").asLong()).isZero();
    assertThat(buckets.get(0).get("meanResolutionSeconds").isNull()).isTrue();
    // Hour 1: A after 1800 s, B after 3600 s, the late-leg case after 200 s.
    assertThat(buckets.get(1).get("resolvedRecoveries").asLong()).isEqualTo(3);
    assertThat(buckets.get(1).get("meanResolutionSeconds").asDouble())
        .isEqualTo((1800 + 3600 + 200) / 3.0);
    // Hour 2: the new case of C after its cancelled one is a retry.
    assertThat(buckets.get(2).get("retries").asLong()).isEqualTo(1);
    // Hour 3: nothing, zeros.
    assertThat(buckets.get(3).get("riskyBags").asLong()).isZero();
    assertThat(buckets.get(3).get("retries").asLong()).isZero();
    assertThat(buckets.get(3).get("meanResolutionSeconds").isNull()).isTrue();

    JsonNode totals = report.get("totals");
    assertThat(totals.get("riskyBags").asLong()).isEqualTo(2);
    assertThat(totals.get("resolvedRecoveries").asLong()).isEqualTo(3);
    assertThat(totals.get("retries").asLong()).isEqualTo(1);
    assertThat(totals.get("meanResolutionSeconds").asDouble()).isEqualTo((1800 + 3600 + 200) / 3.0);
    assertThat(report.has("indexedThrough")).isTrue();
    assertThat(report.has("lagSeconds")).isTrue();
  }

  @Test
  @DisplayName("an airport with no data is 200 with zero-filled hours; an empty range has none")
  void noData() throws Exception {
    JsonNode report = report("QQQ", H0.toString(), H0.plusSeconds(2 * 3600).toString());

    assertThat(report.get("buckets")).hasSize(2);
    assertThat(report.get("totals").get("resolvedRecoveries").asLong()).isZero();
    assertThat(report.get("totals").get("meanResolutionSeconds").isNull()).isTrue();
    assertThat(report(airport, H0.toString(), H0.toString()).get("buckets")).isEmpty();
  }

  @Test
  @DisplayName("validation: IATA code, from <= to, whole hours and at most 31 days")
  void validation() throws Exception {
    String[][] bad = {
      {"ist", H0.toString(), H0.plusSeconds(3600).toString()},
      {"ISTA", H0.toString(), H0.plusSeconds(3600).toString()},
      {"IST", H0.plusSeconds(3600).toString(), H0.toString()},
      {"IST", H0.plusSeconds(60).toString(), H0.plusSeconds(3600).toString()},
      {"IST", H0.toString(), H0.plusSeconds(32L * 24 * 3600).toString()},
      {"IST", "yesterday", H0.toString()},
    };
    for (String[] query : bad) {
      mockMvc
          .perform(
              get("/api/v1/reports/airport/{iata}", query[0])
                  .param("from", query[1])
                  .param("to", query[2])
                  .with(as(Role.DISPATCHER)))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }
    mockMvc
        .perform(
            get("/api/v1/reports/airport/IST").param("from", H0.toString()).with(as(Role.ADMIN)))
        .andExpect(status().isBadRequest());
    // Exactly 31 days is allowed.
    mockMvc
        .perform(
            get("/api/v1/reports/airport/IST")
                .param("from", H0.toString())
                .param("to", H0.plusSeconds(31L * 24 * 3600).toString())
                .with(as(Role.ADMIN)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.buckets.length()").value(31 * 24));
  }
}
