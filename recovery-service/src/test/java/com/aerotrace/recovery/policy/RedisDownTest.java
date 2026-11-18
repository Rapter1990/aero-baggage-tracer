package com.aerotrace.recovery.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aerotrace.common.event.EventEnvelope;
import com.aerotrace.common.event.EventJson;
import com.aerotrace.common.event.RiskChangedPayload;
import com.aerotrace.common.model.RiskLevel;
import com.aerotrace.common.security.JwtClaims;
import com.aerotrace.common.security.Role;
import com.aerotrace.recovery.base.AbstractTestContainerConfiguration;
import com.aerotrace.recovery.consumer.RiskEventHandler;
import com.aerotrace.recovery.repository.RecoveryCaseRepository;
import jakarta.persistence.EntityManager;
import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Redis is down for the whole of this class: its address points at a port nobody listens on. The
 * degraded mode the case study asks for: the service keeps answering from the database, a policy
 * change still takes effect, and above all no duplicate case can be created, because the one active
 * case per bag and leg is decided by PostgreSQL alone.
 */
@SpringBootTest
@AutoConfigureMockMvc
class RedisDownTest extends AbstractTestContainerConfiguration {

  private static final String CASES = "/api/v1/recovery-cases";
  private static final String POLICY = "/api/v1/recovery-policy";

  @Autowired MockMvc mockMvc;
  @Autowired ObjectMapper json;
  @Autowired PolicyService service;
  @Autowired RiskEventHandler risk;
  @Autowired RecoveryCaseRepository cases;
  @Autowired EntityManager entityManager;
  @Autowired PlatformTransactionManager transactionManager;

  @DynamicPropertySource
  static void deadRedis(DynamicPropertyRegistry registry) {
    registry.add("spring.data.redis.host", () -> "127.0.0.1");
    registry.add("spring.data.redis.port", RedisDownTest::unusedPort);
    registry.add("spring.data.redis.password", () -> "irrelevant");
  }

  private static int unusedPort() {
    try (ServerSocket socket = new ServerSocket(0)) {
      return socket.getLocalPort();
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  /** The policy is shared state: every test starts and ends with the seeded 5 minutes. */
  @BeforeEach
  @AfterEach
  void seededPolicy() {
    new TransactionTemplate(transactionManager)
        .executeWithoutResult(
            status ->
                entityManager
                    .createNativeQuery(
                        "update recovery.recovery_policy set minimum_expedite_minutes = 5,"
                            + " version = 1 where policy_key = 'DEFAULT'")
                    .executeUpdate());
  }

  // ------------------------------------------------------------------ helpers

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

  private ResultActions openCase(String bagTag) throws Exception {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("bagTag", bagTag);
    body.put("legId", "LEG-2");
    body.put("reason", "HIGH_CONNECTION_RISK");
    return mockMvc.perform(
        post(CASES)
            .with(as(Role.AGENT))
            .contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(body)));
  }

  // ------------------------------------------------------------------ degraded mode

  @Test
  @DisplayName("Redis down: the policy is read from the database, within the Redis timeout")
  void policyIsReadFromTheDatabase() throws Exception {
    long started = System.nanoTime();

    ResultActions read = mockMvc.perform(get(POLICY).with(as(Role.AGENT)));

    read.andExpect(status().isOk());
    assertThat(data(read).get("minimumExpediteMinutes").asInt()).isEqualTo(5);
    assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(5));
  }

  @Test
  @DisplayName("Redis down: an admin's change still takes effect, with nothing stale left behind")
  void policyChangeStillWorks() throws Exception {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("minimumExpediteMinutes", 25);
    body.put("expectedVersion", 1);

    mockMvc
        .perform(
            put(POLICY)
                .with(as(Role.ADMIN))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body)))
        .andExpect(status().isOk());

    assertThat(service.current().minimumExpediteMinutes()).isEqualTo(25);
    assertThat(service.current().version()).isEqualTo(2);
  }

  @Test
  @DisplayName(
      "AT-19 Redis down: parallel attempts still create exactly one case for a bag and leg")
  void noDuplicateCaseWithoutRedis() throws Exception {
    String bagTag = String.format("%010d", ThreadLocalRandom.current().nextLong(10_000_000_000L));
    int attempts = 10;
    ExecutorService pool = Executors.newFixedThreadPool(attempts);
    CountDownLatch start = new CountDownLatch(1);
    List<Future<Integer>> results = new ArrayList<>();
    for (int i = 0; i < attempts; i++) {
      Callable<Integer> attempt =
          () -> {
            start.await();
            return openCase(bagTag).andReturn().getResponse().getStatus();
          };
      results.add(pool.submit(attempt));
    }
    start.countDown();
    List<Integer> statuses = new ArrayList<>();
    for (Future<Integer> result : results) {
      statuses.add(result.get());
    }
    pool.shutdown();

    assertThat(statuses.stream().filter(s -> s == 201)).hasSize(1);
    assertThat(statuses.stream().filter(s -> s == 409)).hasSize(attempts - 1);
    assertThat(cases.findAll().stream().filter(c -> c.getBagTag().equals(bagTag))).hasSize(1);
  }

  @Test
  @DisplayName("AT-19 Redis down: a risk event still opens exactly one case")
  void riskEventStillOpensACase() {
    String bagTag = String.format("%010d", ThreadLocalRandom.current().nextLong(10_000_000_000L));
    String event =
        EventJson.write(
            new EventEnvelope<>(
                "EV-" + UUID.randomUUID(),
                "baggage.risk.changed",
                1,
                bagTag + ":LEG-2",
                Instant.now(),
                Instant.now(),
                "corr-1",
                new RiskChangedPayload(
                    bagTag,
                    "LEG-2",
                    RiskLevel.LOW,
                    RiskLevel.HIGH,
                    Instant.now().plus(15, ChronoUnit.MINUTES))));

    RiskEventHandler.Outcome first = risk.handle(event);
    RiskEventHandler.Outcome second = risk.handle(event);

    assertThat(first).isEqualTo(RiskEventHandler.Outcome.CASE_OPENED);
    assertThat(second).isEqualTo(RiskEventHandler.Outcome.DUPLICATE);
    assertThat(cases.findAll().stream().filter(c -> c.getBagTag().equals(bagTag))).hasSize(1);
  }
}
