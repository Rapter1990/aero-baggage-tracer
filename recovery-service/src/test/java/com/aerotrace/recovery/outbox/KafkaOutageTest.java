package com.aerotrace.recovery.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aerotrace.common.event.CaseChangedPayload;
import com.aerotrace.common.event.EventEnvelope;
import com.aerotrace.common.event.EventJson;
import com.aerotrace.common.event.Topics;
import com.aerotrace.common.model.RecoveryState;
import com.aerotrace.common.security.JwtClaims;
import com.aerotrace.common.security.Role;
import com.aerotrace.recovery.base.AbstractRecoveryTest;
import com.aerotrace.recovery.repository.OutboxEventRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.kafka.KafkaContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * AT-14 with a real broker: Kafka becomes unreachable while a case moves, the move is committed,
 * and when Kafka is back the committed events are published, in order, each once. The outage is a
 * paused container: connections hang, as they do when a broker is gone from the network.
 */
@SpringBootTest
@AutoConfigureMockMvc
class KafkaOutageTest extends AbstractRecoveryTest {

  private static KafkaContainer kafka;

  @BeforeAll
  static void startKafka() throws Exception {
    org.junit.jupiter.api.Assumptions.assumeTrue(
        DockerClientFactory.instance().isDockerAvailable(), "Docker is not available");
    synchronized (KafkaOutageTest.class) {
      if (kafka == null) {
        kafka = new KafkaContainer("apache/kafka:3.9.1");
        kafka.start();
        Properties admin = new Properties();
        admin.put("bootstrap.servers", kafka.getBootstrapServers());
        try (AdminClient client = AdminClient.create(admin)) {
          client.createTopics(List.of(new NewTopic(Topics.CASE_CHANGED, 1, (short) 1))).all().get();
        }
      }
    }
  }

  @DynamicPropertySource
  static void kafkaProperties(DynamicPropertyRegistry registry) {
    registry.add(
        "spring.kafka.bootstrap-servers",
        () -> {
          try {
            startKafka();
          } catch (Exception e) {
            throw new IllegalStateException(e);
          }
          return kafka.getBootstrapServers();
        });
  }

  @Autowired MockMvc mockMvc;
  @Autowired ObjectMapper json;
  @Autowired OutboxRelay relay;
  @Autowired OutboxEventRepository outbox;
  @Autowired MeterRegistry meters;

  /** Never leave the broker paused for the tests that run after this one. */
  @AfterEach
  void brokerIsBack() {
    resume();
  }

  // ------------------------------------------------------------------ helpers

  private static void pause() {
    DockerClientFactory.instance().client().pauseContainerCmd(kafka.getContainerId()).exec();
  }

  private static void resume() {
    try {
      DockerClientFactory.instance().client().unpauseContainerCmd(kafka.getContainerId()).exec();
    } catch (RuntimeException notPaused) {
      // Already running.
    }
  }

  private static RequestPostProcessor as(Role role) {
    return SecurityMockMvcRequestPostProcessors.jwt()
        .jwt(
            token -> {
              token.subject("user-" + role.name().toLowerCase(java.util.Locale.ROOT));
              token.claim(JwtClaims.ROLES, List.of(role.name()));
            })
        .authorities(new SimpleGrantedAuthority(role.authority()));
  }

  private JsonNode data(ResultActions result) throws Exception {
    return json.readTree(result.andReturn().getResponse().getContentAsString()).get("data");
  }

  private List<ConsumerRecord<String, String>> recordsFor(String caseId, int expected) {
    Properties config = new Properties();
    config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
    config.put(ConsumerConfig.GROUP_ID_CONFIG, "outage-reader-" + UUID.randomUUID());
    config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
    config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
    config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
    List<ConsumerRecord<String, String>> found = new ArrayList<>();
    try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(config)) {
      consumer.subscribe(List.of(Topics.CASE_CHANGED));
      long deadline = System.currentTimeMillis() + 30_000;
      long quietUntil = 0;
      while (System.currentTimeMillis() < deadline) {
        for (ConsumerRecord<String, String> r : consumer.poll(Duration.ofMillis(500))) {
          if (caseId.equals(r.key())) {
            found.add(r);
          }
        }
        if (found.size() >= expected) {
          // Keep listening a little longer: a duplicate would show up here.
          if (quietUntil == 0) {
            quietUntil = System.currentTimeMillis() + 3_000;
          } else if (System.currentTimeMillis() > quietUntil) {
            break;
          }
        }
      }
    }
    return found;
  }

  // ------------------------------------------------------------------ AT-14

  @Test
  @DisplayName(
      "AT-14 Kafka is down during a move: it commits, and the events are published once Kafka is back")
  void committedEventsAreReplayedAfterTheOutage() throws Exception {
    Map<String, Object> open = new LinkedHashMap<>();
    open.put(
        "bagTag", String.format("%010d", ThreadLocalRandom.current().nextLong(10_000_000_000L)));
    open.put("legId", "LEG-2");
    open.put("reason", "HIGH_CONNECTION_RISK");
    JsonNode opened =
        data(
            mockMvc
                .perform(
                    post("/api/v1/recovery-cases")
                        .with(as(Role.AGENT))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(open)))
                .andExpect(status().isCreated()));
    String caseId = opened.get("caseId").asString();

    // The broker disappears, and the case moves anyway: the service does not depend on Kafka to
    // commit a change.
    pause();
    Map<String, Object> move = new LinkedHashMap<>();
    move.put("targetState", "ASSESSING");
    move.put("expectedVersion", 1);
    move.put("reason", "Agent picked it up during the outage");
    mockMvc
        .perform(
            patch("/api/v1/recovery-cases/{id}/transitions", caseId)
                .with(as(Role.DISPATCHER))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(move)))
        .andExpect(status().isOk());

    // Publishing fails, and nothing is lost: both events of the case are still pending.
    relay.publishPending();
    var pending = outbox.findByMessageKeyOrderByCreatedAt(caseId);
    assertThat(pending).hasSize(2);
    assertThat(pending).allSatisfy(e -> assertThat(e.getPublishedAt()).isNull());
    assertThat(meters.get("outbox.pending.count").gauge().value()).isGreaterThanOrEqualTo(2.0);

    // Kafka is back: the committed events are replayed.
    resume();
    await()
        .atMost(Duration.ofSeconds(90))
        .pollInterval(Duration.ofSeconds(1))
        .untilAsserted(
            () -> {
              relay.publishPending();
              assertThat(outbox.findByMessageKeyOrderByCreatedAt(caseId))
                  .allSatisfy(e -> assertThat(e.getPublishedAt()).isNotNull());
            });
    // More relay runs publish nothing again.
    relay.publishPending();
    relay.publishPending();

    // Publishing is at least once, and consumers dedupe on the event id. An attempt that the outage
    // cut off (the producer gave up and the row stayed pending) can still reach the broker when it
    // resumes, so the replay may write that one event a second time. That is allowed; what must
    // hold is: exactly two distinct events, in the order they were committed, none more than twice,
    // and no event of an older version after a newer one.
    List<ConsumerRecord<String, String>> records = recordsFor(caseId, 2);
    Map<String, EventEnvelope<CaseChangedPayload>> distinct = new LinkedHashMap<>();
    Map<String, Integer> copies = new LinkedHashMap<>();
    long lastVersion = 0;
    for (ConsumerRecord<String, String> record : records) {
      EventEnvelope<CaseChangedPayload> event =
          EventJson.read(record.value(), CaseChangedPayload.class);
      distinct.putIfAbsent(event.eventId(), event);
      copies.merge(event.eventId(), 1, Integer::sum);
      assertThat(event.payload().version())
          .as("events of a case stay in order")
          .isGreaterThanOrEqualTo(lastVersion);
      lastVersion = event.payload().version();
    }
    assertThat(distinct).hasSize(2);
    assertThat(copies.values()).allSatisfy(count -> assertThat(count).isBetween(1, 2));
    List<EventEnvelope<CaseChangedPayload>> events = List.copyOf(distinct.values());
    EventEnvelope<CaseChangedPayload> first = events.get(0);
    EventEnvelope<CaseChangedPayload> second = events.get(1);
    assertThat(first.payload().state()).isEqualTo(RecoveryState.OPEN);
    assertThat(first.payload().version()).isEqualTo(1);
    assertThat(second.payload().state()).isEqualTo(RecoveryState.ASSESSING);
    assertThat(second.payload().version()).isEqualTo(2);
    assertThat(second.eventId()).isNotEqualTo(first.eventId());
  }
}
