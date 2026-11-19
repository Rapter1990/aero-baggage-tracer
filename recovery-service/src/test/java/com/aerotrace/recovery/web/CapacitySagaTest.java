package com.aerotrace.recovery.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aerotrace.common.model.RecoveryState;
import com.aerotrace.common.security.JwtClaims;
import com.aerotrace.common.security.Role;
import com.aerotrace.recovery.base.AbstractRecoveryTest;
import com.aerotrace.recovery.base.CapacityStub;
import com.aerotrace.recovery.model.entity.RecoveryCaseEntity;
import com.aerotrace.recovery.repository.RecoveryCaseRepository;
import com.aerotrace.recovery.repository.StateTransitionRepository;
import com.aerotrace.recovery.service.RecoveryCaseService;
import jakarta.persistence.EntityManager;
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
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

/**
 * The capacity saga against a real HTTP stand-in for the capacity-service: reserving on the move to
 * RESERVED, compensating when the move cannot complete, and releasing holds that ran out (AT-12).
 */
@SpringBootTest
@AutoConfigureMockMvc
class CapacitySagaTest extends AbstractRecoveryTest {

  private static final String CASES = "/api/v1/recovery-cases";

  @Autowired MockMvc mockMvc;
  @Autowired ObjectMapper json;
  @Autowired RecoveryCaseRepository cases;
  @Autowired StateTransitionRepository transitions;
  @Autowired RecoveryCaseService service;
  @Autowired EntityManager entityManager;
  @Autowired PlatformTransactionManager transactionManager;

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

  private String code(ResultActions result) throws Exception {
    return json.readTree(result.andReturn().getResponse().getContentAsString())
        .get("code")
        .asString();
  }

  private ResultActions move(JsonNode current, RecoveryState target, String correlationId)
      throws Exception {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("targetState", target.name());
    body.put("expectedVersion", current.get("version").asLong());
    body.put("reason", "Moving to " + target);
    var request =
        patch(CASES + "/{id}/transitions", current.get("caseId").asString())
            .with(as(Role.DISPATCHER))
            .contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(body));
    if (correlationId != null) {
      request.header("X-Correlation-Id", correlationId);
    }
    return mockMvc.perform(request);
  }

  /** A case in ASSESSING, ready to be reserved. */
  private JsonNode assessingCase() throws Exception {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put(
        "bagTag", String.format("%010d", ThreadLocalRandom.current().nextLong(10_000_000_000L)));
    body.put("legId", "LEG-2");
    body.put("reason", "HIGH_CONNECTION_RISK");
    JsonNode opened =
        data(
            mockMvc
                .perform(
                    post(CASES)
                        .with(as(Role.AGENT))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body)))
                .andExpect(status().isCreated()));
    return data(move(opened, RecoveryState.ASSESSING, null).andExpect(status().isOk()));
  }

  private RecoveryCaseEntity stored(JsonNode c) {
    return cases.findById(UUID.fromString(c.get("caseId").asString())).orElseThrow();
  }

  private void execute(String sql, Map<String, Object> named) {
    new TransactionTemplate(transactionManager)
        .executeWithoutResult(
            status -> {
              var query = entityManager.createNativeQuery(sql);
              named.forEach(query::setParameter);
              query.executeUpdate();
            });
  }

  // ------------------------------------------------------------------ reserving

  @Test
  @DisplayName("Saga reserving holds one slot, records it on the case and moves it to RESERVED")
  void reservingHoldsASlot() throws Exception {
    JsonNode assessing = assessingCase();

    ResultActions reserved = move(assessing, RecoveryState.RESERVED, null);

    reserved.andExpect(status().isOk());
    assertThat(data(reserved).get("state").asString()).isEqualTo("RESERVED");
    assertThat(CAPACITY.holds).hasSize(1);
    RecoveryCaseEntity c = stored(assessing);
    assertThat(c.getReservationId()).isEqualTo(CAPACITY.holds.keySet().iterator().next());
    assertThat(c.getReservedAt()).isNotNull();
  }

  @Test
  @DisplayName("Saga the correlation id and the caller's token travel to the capacity-service")
  void correlationIdAndTokenArePropagated() throws Exception {
    JsonNode assessing = assessingCase();

    move(assessing, RecoveryState.RESERVED, "corr-saga-1").andExpect(status().isOk());

    CapacityStub.Call reserve =
        CAPACITY.calls.stream().filter(c -> c.method().equals("POST")).findFirst().orElseThrow();
    assertThat(reserve.headers().get("x-correlation-id")).isEqualTo("corr-saga-1");
    assertThat(reserve.headers().get("authorization")).startsWith("Bearer ");
  }

  @Test
  @DisplayName("Saga no capacity left: 409 CAPACITY_EXCEEDED, the case stays ASSESSING")
  void capacityExceededLeavesTheCaseAlone() throws Exception {
    JsonNode assessing = assessingCase();
    CAPACITY.mode = CapacityStub.Mode.FULL;

    ResultActions refused = move(assessing, RecoveryState.RESERVED, null);

    refused.andExpect(status().isConflict());
    assertThat(code(refused)).isEqualTo("CAPACITY_EXCEEDED");
    assertThat(stored(assessing).getState()).isEqualTo(RecoveryState.ASSESSING);
    assertThat(stored(assessing).getReservationId()).isNull();
    assertThat(CAPACITY.holds).isEmpty();
  }

  @Test
  @DisplayName("Saga capacity-service failing: 503 CAPACITY_UNAVAILABLE and nothing stays held")
  void capacityErrorFallsBackToUnavailable() throws Exception {
    JsonNode assessing = assessingCase();
    CAPACITY.mode = CapacityStub.Mode.ERROR;

    ResultActions refused = move(assessing, RecoveryState.RESERVED, null);

    refused.andExpect(status().isServiceUnavailable());
    assertThat(code(refused)).isEqualTo("CAPACITY_UNAVAILABLE");
    assertThat(stored(assessing).getState()).isEqualTo(RecoveryState.ASSESSING);
    assertThat(CAPACITY.holds).isEmpty();
  }

  @Test
  @DisplayName("AT-12 a lost answer: the call times out, the hold made meanwhile is released")
  void timeoutReleasesTheHoldTheAnswerNeverReported() throws Exception {
    JsonNode assessing = assessingCase();
    CAPACITY.mode = CapacityStub.Mode.SLOW;

    ResultActions refused = move(assessing, RecoveryState.RESERVED, null);

    refused.andExpect(status().isServiceUnavailable());
    assertThat(code(refused)).isEqualTo("CAPACITY_UNAVAILABLE");
    assertThat(CAPACITY.deletes()).isEqualTo(1);
    assertThat(CAPACITY.holds).isEmpty();
    assertThat(stored(assessing).getState()).isEqualTo(RecoveryState.ASSESSING);
  }

  @Test
  @DisplayName("Saga an illegal move holds nothing")
  void illegalMoveDoesNotCallCapacity() throws Exception {
    JsonNode opened = assessingCase();
    // OPEN cannot be skipped: ASSESSING -> IN_TRANSIT is illegal, and capacity is not asked.
    move(opened, RecoveryState.IN_TRANSIT, null).andExpect(status().isConflict());

    assertThat(CAPACITY.calls).isEmpty();
  }

  @Test
  @DisplayName("Saga parallel moves to RESERVED: one wins, every other hold is compensated")
  void losersOfARaceReleaseTheirHold() throws Exception {
    JsonNode assessing = assessingCase();
    int attempts = 6;
    ExecutorService pool = Executors.newFixedThreadPool(attempts);
    CountDownLatch start = new CountDownLatch(1);
    List<Future<Integer>> results = new ArrayList<>();
    for (int i = 0; i < attempts; i++) {
      Callable<Integer> attempt =
          () -> {
            start.await();
            return move(assessing, RecoveryState.RESERVED, null)
                .andReturn()
                .getResponse()
                .getStatus();
          };
      results.add(pool.submit(attempt));
    }
    start.countDown();
    List<Integer> statuses = new ArrayList<>();
    for (Future<Integer> result : results) {
      statuses.add(result.get());
    }
    pool.shutdown();

    assertThat(statuses.stream().filter(s -> s == 200)).hasSize(1);
    assertThat(statuses.stream().filter(s -> s == 409)).hasSize(attempts - 1);
    // Exactly the winner's hold is left: no leaked holds, no double booking.
    assertThat(CAPACITY.holds).hasSize(1);
    assertThat(CAPACITY.holds.keySet()).containsExactly(stored(assessing).getReservationId());
  }

  // ------------------------------------------------------------------ releasing

  @Test
  @DisplayName("Saga cancelling a reserved case releases its hold")
  void cancellingReleasesTheHold() throws Exception {
    JsonNode reserved = data(move(assessingCase(), RecoveryState.RESERVED, null));
    assertThat(CAPACITY.holds).hasSize(1);

    move(reserved, RecoveryState.CANCELLED, null).andExpect(status().isOk());

    assertThat(CAPACITY.holds).isEmpty();
    assertThat(stored(reserved).getReservationId()).isNull();
  }

  @Test
  @DisplayName("Saga a hold stays while the bag is in transit and is released when it is resolved")
  void resolvingReleasesTheHold() throws Exception {
    JsonNode reserved = data(move(assessingCase(), RecoveryState.RESERVED, null));
    JsonNode inTransit = data(move(reserved, RecoveryState.IN_TRANSIT, null));
    assertThat(CAPACITY.holds).hasSize(1);

    move(inTransit, RecoveryState.RESOLVED, null).andExpect(status().isOk());

    assertThat(CAPACITY.holds).isEmpty();
    assertThat(stored(inTransit).getReservationId()).isNull();
  }

  @Test
  @DisplayName("AT-12 a hold that ran out is released and its case cancelled with the reason")
  void expiredHoldIsReleasedAndTheCaseCancelled() throws Exception {
    JsonNode reserved = data(move(assessingCase(), RecoveryState.RESERVED, null));
    UUID caseId = UUID.fromString(reserved.get("caseId").asString());
    execute(
        "update recovery.recovery_case set reserved_at = :old where id = :id",
        Map.of("old", Instant.now().minus(1, ChronoUnit.HOURS), "id", caseId));

    int expired = service.expireHolds(Instant.now().minus(15, ChronoUnit.MINUTES));

    assertThat(expired).isGreaterThanOrEqualTo(1);
    RecoveryCaseEntity c = stored(reserved);
    assertThat(c.getState()).isEqualTo(RecoveryState.CANCELLED);
    assertThat(c.getReservationId()).isNull();
    assertThat(CAPACITY.holds).isEmpty();
    var last = transitions.findByCaseIdOrderByVersionAfterAsc(caseId).getLast();
    assertThat(last.getToState()).isEqualTo(RecoveryState.CANCELLED);
    assertThat(last.getActor()).isEqualTo("system");
    assertThat(last.getReason()).startsWith("RESERVATION_TIMEOUT");
  }

  @Test
  @DisplayName("AT-12 a hold that is still fresh is left alone")
  void freshHoldIsNotExpired() throws Exception {
    JsonNode reserved = data(move(assessingCase(), RecoveryState.RESERVED, null));

    service.expireHolds(Instant.now().minus(15, ChronoUnit.MINUTES));

    assertThat(stored(reserved).getState()).isEqualTo(RecoveryState.RESERVED);
    assertThat(CAPACITY.holds).hasSize(1);
  }

  @Test
  @DisplayName("Saga the hold is confirmed after the move commits, so capacity never expires it")
  void holdIsConfirmedAfterTheMove() throws Exception {
    JsonNode reserved = data(move(assessingCase(), RecoveryState.RESERVED, null));

    assertThat(CAPACITY.confirmed).hasSize(1);
    assertThat(stored(reserved).getHoldConfirmedAt()).isNotNull();
  }

  @Test
  @DisplayName(
      "Saga a confirmation that fails does not fail the move and is retried by the sweeper")
  void failedConfirmationIsRetried() throws Exception {
    CAPACITY.confirmFails = true;

    JsonNode reserved = data(move(assessingCase(), RecoveryState.RESERVED, null));

    assertThat(stored(reserved).getState()).isEqualTo(RecoveryState.RESERVED);
    assertThat(stored(reserved).getHoldConfirmedAt()).isNull();
    assertThat(service.retryConfirms()).isZero();

    CAPACITY.confirmFails = false;
    assertThat(service.retryConfirms()).isGreaterThanOrEqualTo(1);
    assertThat(stored(reserved).getHoldConfirmedAt()).isNotNull();
    assertThat(CAPACITY.confirmed).hasSize(1);
  }

  @Test
  @DisplayName("Saga a leg without a capacity resource is told apart but still answers 409")
  void unprovisionedLegAnswersNoCapacity() throws Exception {
    JsonNode assessing = assessingCase();
    CAPACITY.mode = CapacityStub.Mode.UNPROVISIONED;

    move(assessing, RecoveryState.RESERVED, null).andExpect(status().isConflict());

    assertThat(stored(assessing).getState()).isEqualTo(RecoveryState.ASSESSING);
    assertThat(CAPACITY.holds).isEmpty();
  }

  @Test
  @DisplayName(
      "Saga a 409 that is not 'full' is not taken for no capacity: 503, nothing stays held")
  void otherConflictIsNotNoCapacity() throws Exception {
    JsonNode assessing = assessingCase();
    CAPACITY.mode = CapacityStub.Mode.CONFLICT;

    move(assessing, RecoveryState.RESERVED, null).andExpect(status().isServiceUnavailable());

    assertThat(stored(assessing).getState()).isEqualTo(RecoveryState.ASSESSING);
    assertThat(CAPACITY.holds).isEmpty();
  }

  @Test
  @DisplayName("Saga a release that fails is remembered on the case and retried")
  void failedReleaseIsRetried() throws Exception {
    JsonNode reserved = data(move(assessingCase(), RecoveryState.RESERVED, null));
    CAPACITY.releaseFails = true;

    move(reserved, RecoveryState.CANCELLED, null).andExpect(status().isOk());

    // The case is cancelled, but the hold could not be released: it is still recorded.
    assertThat(stored(reserved).getState()).isEqualTo(RecoveryState.CANCELLED);
    assertThat(stored(reserved).getReservationId()).isNotNull();
    assertThat(CAPACITY.holds).hasSize(1);

    // While the release keeps failing, nothing counts as released.
    assertThat(service.retryReleases()).isZero();
    assertThat(stored(reserved).getReservationId()).isNotNull();

    CAPACITY.releaseFails = false;
    assertThat(service.retryReleases()).isGreaterThanOrEqualTo(1);

    assertThat(stored(reserved).getReservationId()).isNull();
    assertThat(CAPACITY.holds).isEmpty();

    // The retry runs without a user token, so it proves itself with the shared service token.
    CapacityStub.Call retry =
        CAPACITY.calls.stream()
            .filter(c -> c.method().equals("DELETE"))
            .reduce((first, second) -> second)
            .orElseThrow();
    assertThat(retry.headers().get("x-service-token")).isEqualTo("test-service-token");
    assertThat(retry.headers()).doesNotContainKey("authorization");
  }
}
