package com.aerotrace.search.status;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aerotrace.common.event.Topics;
import com.aerotrace.common.model.RecoveryState;
import com.aerotrace.common.security.Role;
import com.aerotrace.search.base.AbstractSearchTest;
import com.aerotrace.search.base.Events;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.Properties;
import java.util.UUID;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The lag metrics and {@code GET /api/v1/search/index/status} with the listeners running. */
@SpringBootTest
@AutoConfigureMockMvc
class IndexStatusApiTest extends AbstractSearchTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  /** One value: the supplier is read once per use, and every listener must get the same group. */
  private static final String GROUP = "search-status-" + UUID.randomUUID();

  @DynamicPropertySource
  static void consumers(DynamicPropertyRegistry registry) {
    registry.add("aerotrace.search.consumers.enabled", () -> "true");
    registry.add("aerotrace.search.consumers.group-id", () -> GROUP);
  }

  @Autowired MockMvc mockMvc;
  @Autowired MeterRegistry meters;

  private JsonNode indexStatus() throws Exception {
    return JSON.readTree(
            mockMvc
                .perform(get("/api/v1/search/index/status").with(as(Role.DISPATCHER)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString())
        .get("data");
  }

  @Test
  @DisplayName(
      "once the events are indexed the lag is 0 events and 0 seconds, the watermark is"
          + " set and the gauges are exported")
  void caughtUp() throws Exception {
    String caseId = newId("case");
    Properties config = new Properties();
    config.put("bootstrap.servers", kafkaBootstrapServers());
    config.put("key.serializer", StringSerializer.class.getName());
    config.put("value.serializer", StringSerializer.class.getName());
    try (KafkaProducer<String, String> producer = new KafkaProducer<>(config)) {
      producer
          .send(
              new ProducerRecord<>(
                  Topics.CASE_CHANGED,
                  caseId,
                  Events.caseV1(caseId, newBagTag(), newId("leg"), RecoveryState.OPEN, 1)))
          .get();
    }

    await()
        .atMost(Duration.ofSeconds(90))
        .until(() -> indexStatus().get("lagEvents").toString(), "0"::equals);
    JsonNode status = indexStatus();

    assertThat(status.get("lagSeconds").asDouble()).isZero();
    assertThat(Instant.parse(status.get("indexedThrough").asString()))
        .isAfter(Instant.now().minusSeconds(60));
    assertThat(status.get("indexes").get("cases").get("read").asString()).startsWith("cases-v");
    assertThat(status.get("indexes").get("cases").get("live")).hasSize(1);
    assertThat(status.get("indexes").get("journeys").get("documents").asLong()).isNotNegative();
    assertThat(meters.get("search.index.lag").gauge().value()).isZero();
    assertThat(meters.get("search.index.lag.events").gauge().value()).isZero();
    assertThat(meters.get("search.index.delay").timer().count()).isPositive();
    assertThat(meters.find("search.index.events").tag("outcome", "indexed").counters())
        .isNotEmpty();
  }

  @Test
  @DisplayName("behind, the lag is the age of the newest indexed event; unknown before the first")
  void lagSeconds() {
    Instant now = Instant.parse("2026-01-01T12:00:00Z");

    assertThat(LagMonitor.lagSeconds(0, null, now)).isZero();
    assertThat(LagMonitor.lagSeconds(3, now.minusSeconds(42), now)).isEqualTo(42.0);
    assertThat(LagMonitor.lagSeconds(3, null, now)).isNull();
    assertThat(LagMonitor.lagSeconds(3, now.plusSeconds(5), now)).isZero();
  }
}
