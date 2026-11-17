package com.aerotrace.recovery.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aerotrace.common.event.EventEnvelope;
import com.aerotrace.common.event.EventJson;
import com.aerotrace.common.event.RiskChangedPayload;
import com.aerotrace.common.model.RiskLevel;
import com.aerotrace.common.security.JwtClaims;
import com.aerotrace.common.security.Role;
import com.aerotrace.recovery.base.AbstractRecoveryTest;
import com.aerotrace.recovery.consumer.RiskEventHandler;
import com.aerotrace.recovery.repository.RecoveryCaseRepository;
import com.aerotrace.recovery.strategy.RecoveryAction;
import jakarta.persistence.EntityManager;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** The policy cache against a real Redis and PostgreSQL: TTL, eviction, and who may change it. */
@SpringBootTest
@AutoConfigureMockMvc
class PolicyCacheTest extends AbstractRecoveryTest {

  private static final String POLICY = "/api/v1/recovery-policy";
  private static final String KEY = PolicyCache.keyOf("DEFAULT");

  @Autowired MockMvc mockMvc;
  @Autowired ObjectMapper json;
  @Autowired PolicyService service;
  @Autowired StringRedisTemplate redis;
  @Autowired EntityManager entityManager;
  @Autowired PlatformTransactionManager transactionManager;
  @Autowired RiskEventHandler risk;
  @Autowired RecoveryCaseRepository cases;

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
    redis.delete(KEY);
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

  private ResultActions change(int minutes, long expectedVersion, RequestPostProcessor caller)
      throws Exception {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("minimumExpediteMinutes", minutes);
    body.put("expectedVersion", expectedVersion);
    return mockMvc.perform(
        put(POLICY)
            .with(caller)
            .contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(body)));
  }

  private JsonNode data(ResultActions result) throws Exception {
    return json.readTree(result.andReturn().getResponse().getContentAsString()).get("data");
  }

  private void setDatabasePolicy(int minutes) {
    new TransactionTemplate(transactionManager)
        .executeWithoutResult(
            status ->
                entityManager
                    .createNativeQuery(
                        "update recovery.recovery_policy set minimum_expedite_minutes = "
                            + minutes
                            + " where policy_key = 'DEFAULT'")
                    .executeUpdate());
  }

  // ------------------------------------------------------------------ cache behaviour

  @Test
  @DisplayName("Redis a read fills the cache with a TTL, and the next read is served from it")
  void readFillsTheCacheWithATtl() {
    assertThat(redis.hasKey(KEY)).isFalse();

    PolicyView first = service.current();

    assertThat(first.minimumExpediteMinutes()).isEqualTo(5);
    assertThat(redis.opsForValue().get(KEY)).isEqualTo("5:1");
    Long ttl = redis.getExpire(KEY);
    assertThat(ttl).isBetween(1L, 4L);

    // The database changes behind the cache's back: the cached value is still served.
    setDatabasePolicy(9);
    assertThat(service.current().minimumExpediteMinutes()).isEqualTo(5);
  }

  @Test
  @DisplayName("Redis a cached policy expires after its TTL and the database is read again")
  void cachedPolicyExpires() {
    service.current();
    setDatabasePolicy(9);

    await()
        .atMost(Duration.ofSeconds(15))
        .untilAsserted(() -> assertThat(redis.hasKey(KEY)).isFalse());

    assertThat(service.current().minimumExpediteMinutes()).isEqualTo(9);
  }

  @Test
  @DisplayName("Redis an entry this service did not write is dropped, and the database decides")
  void corruptEntryIsIgnored() {
    redis.opsForValue().set(KEY, "garbage");

    PolicyView policy = service.current();

    assertThat(policy.minimumExpediteMinutes()).isEqualTo(5);
    assertThat(redis.opsForValue().get(KEY)).isEqualTo("5:1");
  }

  // ------------------------------------------------------------------ changing the policy

  @Test
  @DisplayName("Policy an admin's change is committed, evicts the cache and is seen at once")
  void changeEvictsTheCache() throws Exception {
    service.current();
    assertThat(redis.hasKey(KEY)).isTrue();

    ResultActions changed = change(20, 1, as(Role.ADMIN));

    changed.andExpect(status().isOk());
    assertThat(data(changed).get("minimumExpediteMinutes").asInt()).isEqualTo(20);
    assertThat(data(changed).get("version").asLong()).isEqualTo(2);
    assertThat(redis.hasKey(KEY)).isFalse();
    ResultActions read = mockMvc.perform(get(POLICY).with(as(Role.AGENT)));
    read.andExpect(status().isOk());
    assertThat(data(read).get("minimumExpediteMinutes").asInt()).isEqualTo(20);
    assertThat(redis.opsForValue().get(KEY)).isEqualTo("20:2");
  }

  @Test
  @DisplayName("Policy a change against a stale version is a 409 and changes nothing")
  void staleVersionIsAConflict() throws Exception {
    change(20, 1, as(Role.ADMIN)).andExpect(status().isOk());

    ResultActions stale = change(30, 1, as(Role.ADMIN));

    stale.andExpect(status().isConflict());
    assertThat(service.current().minimumExpediteMinutes()).isEqualTo(20);
  }

  @Test
  @DisplayName("Policy only an admin may change it: agents, dispatchers and passengers get 403")
  void onlyAdminsMayChange() throws Exception {
    change(20, 1, as(Role.AGENT)).andExpect(status().isForbidden());
    change(20, 1, as(Role.DISPATCHER)).andExpect(status().isForbidden());
    change(20, 1, as(Role.PASSENGER)).andExpect(status().isForbidden());

    assertThat(service.current().minimumExpediteMinutes()).isEqualTo(5);
  }

  @Test
  @DisplayName("Policy staff may read it, a passenger may not")
  void staffMayRead() throws Exception {
    mockMvc.perform(get(POLICY).with(as(Role.AGENT))).andExpect(status().isOk());
    mockMvc.perform(get(POLICY).with(as(Role.DISPATCHER))).andExpect(status().isOk());
    mockMvc.perform(get(POLICY).with(as(Role.ADMIN))).andExpect(status().isOk());
    mockMvc.perform(get(POLICY).with(as(Role.PASSENGER))).andExpect(status().isForbidden());
    mockMvc.perform(get(POLICY)).andExpect(status().isUnauthorized());
  }

  @Test
  @DisplayName("Policy a value outside 0 to 720 minutes or a missing field is a 400")
  void invalidValuesAreRefused() throws Exception {
    change(721, 1, as(Role.ADMIN)).andExpect(status().isBadRequest());
    change(-1, 1, as(Role.ADMIN)).andExpect(status().isBadRequest());
    change(10, 0, as(Role.ADMIN)).andExpect(status().isBadRequest());
    mockMvc
        .perform(
            put(POLICY).with(as(Role.ADMIN)).contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isBadRequest());
  }

  // ------------------------------------------------------------------ the strategy follows it

  private String highRiskWithMinutesLeft(String bagTag, long minutes) {
    return EventJson.write(
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
                Instant.now().plus(minutes, ChronoUnit.MINUTES))));
  }

  private RecoveryAction plannedFor(String bagTag) {
    return cases.findAll().stream()
        .filter(c -> c.getBagTag().equals(bagTag))
        .findFirst()
        .orElseThrow()
        .getPlannedAction();
  }

  @Test
  @DisplayName("Strategy a raised minimum expedite time reroutes a bag that used to be expedited")
  void policyChangesThePlannedAction() throws Exception {
    String before = String.format("%010d", ThreadLocalRandom.current().nextLong(10_000_000_000L));
    String after = String.format("%010d", ThreadLocalRandom.current().nextLong(10_000_000_000L));
    risk.handle(highRiskWithMinutesLeft(before, 20));

    change(30, 1, as(Role.ADMIN)).andExpect(status().isOk());
    risk.handle(highRiskWithMinutesLeft(after, 20));

    assertThat(plannedFor(before)).isEqualTo(RecoveryAction.EXPEDITED_TRANSFER);
    assertThat(plannedFor(after)).isEqualTo(RecoveryAction.REROUTE_NEXT_FLIGHT);
  }
}
