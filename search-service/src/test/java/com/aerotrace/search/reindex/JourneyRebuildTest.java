package com.aerotrace.search.reindex;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.aerotrace.common.event.CaseChangedPayload;
import com.aerotrace.common.event.LegSnapshot;
import com.aerotrace.common.event.Topics;
import com.aerotrace.common.export.BagExport;
import com.aerotrace.common.export.BagExport.LatestScan;
import com.aerotrace.common.model.CaseTrigger;
import com.aerotrace.common.model.RecoveryState;
import com.aerotrace.common.model.RiskLevel;
import com.aerotrace.common.security.Role;
import com.aerotrace.search.base.AbstractSearchTest;
import com.aerotrace.search.base.Events;
import com.aerotrace.search.base.InMemorySource;
import com.aerotrace.search.elastic.IndexAdmin;
import com.aerotrace.search.elastic.IndexFamily;
import com.aerotrace.search.indexer.EventIndexer;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Rebuilding everything: journeys from the bag export (stand-in for #28), their cases from the case
 * export (stand-in for #29) and their risk from a replay of {@code baggage.risk.changed.v1} on a
 * real Kafka; the read aliases of both families move, every exported bag is checked to be in the
 * new index, and a rebuilt journey sorts like the live-built one.
 *
 * <p>Each test filters by an airline only it uses, so the shared indexes cannot mix their data.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(InMemorySource.Config.class)
class JourneyRebuildTest extends AbstractSearchTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Autowired MockMvc mockMvc;
  @Autowired InMemorySource source;
  @Autowired IndexAdmin admin;
  @Autowired EventIndexer indexer;
  @Autowired ElasticsearchClient client;

  @BeforeEach
  void freshSource() {
    awaitNoLiveRebuildLock(client);
    source.reset();
  }

  private static void publishRisk(String bag, String leg, Instant at) throws Exception {
    Properties config = new Properties();
    config.put("bootstrap.servers", kafkaBootstrapServers());
    config.put("key.serializer", StringSerializer.class.getName());
    config.put("value.serializer", StringSerializer.class.getName());
    try (KafkaProducer<String, String> producer = new KafkaProducer<>(config)) {
      producer
          .send(
              new ProducerRecord<>(
                  Topics.RISK_CHANGED, bag + ":" + leg, Events.risk(bag, leg, RiskLevel.HIGH, at)))
          .get();
    }
  }

  private JsonNode search(String query) throws Exception {
    return JSON.readTree(
            mockMvc
                .perform(get("/api/v1/search/bags?" + query).with(as(Role.ADMIN)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString())
        .get("data");
  }

  private List<String> order(String airline) throws Exception {
    client.indices().refresh(r -> r.index(IndexFamily.JOURNEYS.readAlias()));
    List<String> tags = new ArrayList<>();
    search("airline=" + airline + "&size=100")
        .get("items")
        .forEach(item -> tags.add(item.get("bagTag").asString()));
    return tags;
  }

  private JsonNode rebuild(String scope) throws Exception {
    String body =
        mockMvc
            .perform(
                post("/api/v1/admin/search/reindex")
                    .param("scope", scope)
                    .header("Idempotency-Key", UUID.randomUUID().toString())
                    .with(as(Role.ADMIN)))
            .andExpect(status().isAccepted())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String jobId = JSON.readTree(body).get("data").get("jobId").asString();
    await()
        .atMost(Duration.ofSeconds(120))
        .until(() -> !"RUNNING".equals(job(jobId).get("status").asString()));
    return job(jobId);
  }

  private JsonNode job(String jobId) throws Exception {
    return JSON.readTree(
            mockMvc
                .perform(get("/api/v1/admin/search/reindex/{id}", jobId).with(as(Role.ADMIN)))
                .andReturn()
                .getResponse()
                .getContentAsString())
        .get("data");
  }

  @Test
  @DisplayName(
      "a full rebuild restores journeys with their itinerary, latest scan, case and"
          + " replayed risk, checks every exported bag, and moves both read aliases")
  void fullRebuild() throws Exception {
    String airline = "J7";
    String origin = "WJR";
    Set<String> bags = new HashSet<>();
    Instant at = Instant.parse("2026-07-01T10:00:00Z");
    for (int i = 0; i < 30; i++) {
      String bag = newBagTag();
      String leg = newId("leg");
      bags.add(bag);
      source.bags.put(
          bag,
          new BagExport(
              bag,
              List.of(new LegSnapshot(leg, airline + "20" + i, airline, origin, "IST", at)),
              new LatestScan(origin, "LOADED", leg, at.plusSeconds(i), i)));
      if (i < 4) {
        publishRisk(bag, leg, at.plusSeconds(60));
      }
      if (i < 2) {
        String caseId = newId("case");
        source.cases.put(
            caseId,
            new CaseChangedPayload(
                caseId,
                bag,
                leg,
                RecoveryState.ASSESSING,
                2,
                null,
                at,
                null,
                CaseTrigger.RISK,
                null));
      }
    }
    String casesBefore = admin.readIndex(IndexFamily.CASES);
    String journeysBefore = admin.readIndex(IndexFamily.JOURNEYS);

    JsonNode job = rebuild("ALL");

    assertThat(job.get("status").asString()).isEqualTo("SUCCEEDED");
    JsonNode journeys = job.get("results").get("journeys");
    assertThat(journeys.get("sourceCount").asLong()).isEqualTo(30);
    assertThat(journeys.get("missing").asLong()).isZero();
    assertThat(journeys.get("sourceChecksum").asString())
        .isEqualTo(journeys.get("indexChecksum").asString());
    assertThat(admin.readIndex(IndexFamily.CASES)).isNotEqualTo(casesBefore);
    assertThat(admin.readIndex(IndexFamily.JOURNEYS)).isNotEqualTo(journeysBefore);
    JsonNode all = search("airline=" + airline + "&size=100");
    Set<String> found = new HashSet<>();
    all.get("items").forEach(item -> found.add(item.get("bagTag").asString()));
    assertThat(found).isEqualTo(bags);
    assertThat(search("airline=" + airline + "&riskLevel=HIGH").get("items")).hasSize(4);
    assertThat(search("airline=" + airline + "&caseState=ASSESSING").get("items")).hasSize(2);
    JsonNode one = search("airline=" + airline + "&size=1").get("items").get(0);
    assertThat(one.get("latestScan").get("airport").asString()).isEqualTo(origin);
    assertThat(one.get("legs").get(0).get("origin").asString()).isEqualTo(origin);
    assertThat(source.calls)
        .allSatisfy(call -> assertThat(call).matches("GET (cases|bags) .*"))
        .anyMatch(call -> call.startsWith("GET bags"));
  }

  @Test
  @DisplayName(
      "a rebuilt journey without a scan has a deterministic updatedAt and sorts like the"
          + " live-built one")
  void rebuiltOrderEqualsLiveOrder() throws Exception {
    String airline = "J8";
    String origin = "WJO";
    Instant base = Instant.parse("2026-08-01T00:00:00Z");
    for (int i = 0; i < 8; i++) {
      String bag = newBagTag();
      String leg = newId("leg");
      // Departures 3 hours apart; live, the bag registers 2 hours before and (every other bag)
      // is scanned 1 hour before its departure.
      Instant departure = base.plus(Duration.ofHours(3L * i));
      LegSnapshot snapshot =
          new LegSnapshot(leg, airline + (100 + i), airline, origin, "IST", departure);
      boolean scanned = i % 2 == 0;
      Instant scanAt = departure.minus(Duration.ofHours(1));
      indexer.indexRegistrations(
          List.of(
              Events.record(
                  Topics.BAG_REGISTERED,
                  bag,
                  Events.registered(
                      bag, List.of(snapshot), departure.minus(Duration.ofHours(2))))));
      if (scanned) {
        indexer.indexScans(
            List.of(
                Events.record(
                    Topics.SCAN_RECEIVED, bag, Events.scan(bag, origin, scanAt, 1, leg))));
      }
      source.bags.put(
          bag,
          new BagExport(
              bag,
              List.of(snapshot),
              scanned ? new LatestScan(origin, "LOADED", leg, scanAt, 1) : null));
    }
    List<String> live = order(airline);
    assertThat(live).hasSize(8);

    JsonNode job = rebuild("JOURNEYS");

    assertThat(job.get("status").asString()).isEqualTo("SUCCEEDED");
    assertThat(order(airline)).isEqualTo(live);
    JsonNode items = search("airline=" + airline + "&size=100").get("items");
    items.forEach(item -> assertThat(item.get("updatedAt").isNull()).isFalse());
  }
}
