package com.aerotrace.search.reindex;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aerotrace.common.event.Topics;
import com.aerotrace.common.model.RecoveryState;
import com.aerotrace.common.model.RiskLevel;
import com.aerotrace.common.security.Role;
import com.aerotrace.search.base.AbstractSearchTest;
import com.aerotrace.search.base.Events;
import com.aerotrace.search.base.InMemorySource;
import com.aerotrace.search.elastic.IndexAdmin;
import com.aerotrace.search.elastic.IndexFamily;
import com.aerotrace.search.indexer.model.CaseChange;
import com.aerotrace.search.indexer.model.LegSnapshot;
import com.aerotrace.search.reindex.SourceExportClient.BagExport;
import com.aerotrace.search.reindex.SourceExportClient.LatestScan;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
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
 * real Kafka; the read aliases of both families move.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(InMemorySource.Config.class)
class JourneyRebuildTest extends AbstractSearchTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Autowired MockMvc mockMvc;
  @Autowired InMemorySource source;
  @Autowired IndexAdmin admin;

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

  @Test
  @DisplayName(
      "a full rebuild restores journeys with their itinerary, latest scan, case and"
          + " replayed risk, and moves both read aliases")
  void fullRebuild() throws Exception {
    source.reset();
    String airline = "J" + (int) (Math.random() * 10);
    java.util.Random random = new java.util.Random();
    String origin = "W" + (char) ('A' + random.nextInt(26)) + (char) ('A' + random.nextInt(26));
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
            new CaseChange(
                caseId, bag, leg, RecoveryState.ASSESSING, 2, "r", at, null, "RISK", null));
      }
    }
    String casesBefore = admin.readIndex(IndexFamily.CASES);
    String journeysBefore = admin.readIndex(IndexFamily.JOURNEYS);

    String body =
        mockMvc
            .perform(
                post("/api/v1/admin/search/reindex")
                    .header("Idempotency-Key", UUID.randomUUID().toString())
                    .with(as(Role.ADMIN)))
            .andExpect(status().isAccepted())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String jobId = JSON.readTree(body).get("data").get("jobId").asString();
    await()
        .atMost(Duration.ofSeconds(120))
        .until(
            () ->
                !"RUNNING"
                    .equals(
                        JSON.readTree(
                                mockMvc
                                    .perform(
                                        get("/api/v1/admin/search/reindex/{id}", jobId)
                                            .with(as(Role.ADMIN)))
                                    .andReturn()
                                    .getResponse()
                                    .getContentAsString())
                            .get("data")
                            .get("status")
                            .asString()));

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
}
