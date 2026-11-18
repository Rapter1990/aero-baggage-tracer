package com.aerotrace.recovery.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aerotrace.common.model.RecoveryState;
import com.aerotrace.common.security.JwtClaims;
import com.aerotrace.common.security.Role;
import com.aerotrace.recovery.base.AbstractRecoveryTest;
import com.aerotrace.recovery.repository.OutboxEventRepository;
import com.aerotrace.recovery.repository.StateTransitionRepository;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Retried moves (Idempotency-Key) and the assignment of a case to a handler. */
@SpringBootTest
@AutoConfigureMockMvc
class CaseOperationsApiTest extends AbstractRecoveryTest {

  private static final String CASES = "/api/v1/recovery-cases";

  @Autowired MockMvc mockMvc;
  @Autowired ObjectMapper json;
  @Autowired StateTransitionRepository transitions;
  @Autowired OutboxEventRepository outbox;

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

  private JsonNode openedCase() throws Exception {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put(
        "bagTag", String.format("%010d", ThreadLocalRandom.current().nextLong(10_000_000_000L)));
    body.put("legId", "LEG-2");
    body.put("reason", "HIGH_CONNECTION_RISK");
    return data(
        mockMvc
            .perform(
                post(CASES)
                    .with(as(Role.AGENT))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(body)))
            .andExpect(status().isCreated()));
  }

  private ResultActions move(String caseId, RecoveryState target, long expectedVersion, String key)
      throws Exception {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("targetState", target.name());
    body.put("expectedVersion", expectedVersion);
    body.put("reason", "Moving to " + target);
    var request =
        patch(CASES + "/{id}/transitions", caseId)
            .with(as(Role.DISPATCHER))
            .contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(body));
    if (key != null) {
      request.header("Idempotency-Key", key);
    }
    return mockMvc.perform(request);
  }

  private ResultActions assign(
      String caseId, String assignee, Long expectedVersion, RequestPostProcessor caller)
      throws Exception {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("assignee", assignee);
    if (expectedVersion != null) {
      body.put("expectedVersion", expectedVersion);
    }
    return mockMvc.perform(
        put(CASES + "/{id}/assignment", caseId)
            .with(caller)
            .contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(body)));
  }

  private static String newKey() {
    return "key-" + UUID.randomUUID();
  }

  // ------------------------------------------------------------------ Idempotency-Key on a move

  @Test
  @DisplayName("Idempotency-Key a retried move returns the original answer and moves nothing twice")
  void retriedMoveReturnsTheOriginalAnswer() throws Exception {
    JsonNode opened = openedCase();
    String caseId = opened.get("caseId").asString();
    String key = newKey();
    JsonNode first = data(move(caseId, RecoveryState.ASSESSING, 1, key).andExpect(status().isOk()));

    ResultActions retry = move(caseId, RecoveryState.ASSESSING, 1, key);

    retry.andExpect(status().isOk());
    assertThat(data(retry).get("state").asString()).isEqualTo("ASSESSING");
    assertThat(data(retry).get("version").asLong()).isEqualTo(first.get("version").asLong());
    assertThat(transitions.findByCaseIdOrderByVersionAfterAsc(UUID.fromString(caseId))).hasSize(2);
    assertThat(outbox.findByMessageKeyOrderByCreatedAt(caseId)).hasSize(2);
  }

  @Test
  @DisplayName("AT-12 a retried move to RESERVED holds capacity once")
  void retriedReservationHoldsOnce() throws Exception {
    String caseId = openedCase().get("caseId").asString();
    move(caseId, RecoveryState.ASSESSING, 1, null).andExpect(status().isOk());
    String key = newKey();
    move(caseId, RecoveryState.RESERVED, 2, key).andExpect(status().isOk());

    ResultActions retry = move(caseId, RecoveryState.RESERVED, 2, key);

    retry.andExpect(status().isOk());
    assertThat(data(retry).get("version").asLong()).isEqualTo(3);
    assertThat(CAPACITY.holds).hasSize(1);
    assertThat(CAPACITY.calls.stream().filter(c -> c.method().equals("POST"))).hasSize(1);
  }

  @Test
  @DisplayName("Idempotency-Key a retry still gets the answer after the case moved on")
  void retryAfterTheCaseMovedOn() throws Exception {
    String caseId = openedCase().get("caseId").asString();
    String key = newKey();
    move(caseId, RecoveryState.ASSESSING, 1, key).andExpect(status().isOk());
    move(caseId, RecoveryState.CANCELLED, 2, null).andExpect(status().isOk());

    ResultActions retry = move(caseId, RecoveryState.ASSESSING, 1, key);

    retry.andExpect(status().isOk());
    assertThat(data(retry).get("state").asString()).isEqualTo("ASSESSING");
    assertThat(data(retry).get("version").asLong()).isEqualTo(2);
  }

  @Test
  @DisplayName("Idempotency-Key reused for a different move is a conflict")
  void keyReusedForAnotherMoveIsAConflict() throws Exception {
    String caseId = openedCase().get("caseId").asString();
    String key = newKey();
    move(caseId, RecoveryState.ASSESSING, 1, key).andExpect(status().isOk());

    ResultActions other = move(caseId, RecoveryState.CANCELLED, 1, key);

    other.andExpect(status().isConflict());
    assertThat(code(other)).isEqualTo("VERSION_CONFLICT");
  }

  @Test
  @DisplayName("Idempotency-Key a key belongs to one case: another case may use the same key")
  void keyIsPerCase() throws Exception {
    String key = newKey();
    String first = openedCase().get("caseId").asString();
    String second = openedCase().get("caseId").asString();

    move(first, RecoveryState.ASSESSING, 1, key).andExpect(status().isOk());
    move(second, RecoveryState.ASSESSING, 1, key).andExpect(status().isOk());
  }

  @Test
  @DisplayName("Idempotency-Key without a key a retried move is a version conflict, as before")
  void retryWithoutAKeyIsAConflict() throws Exception {
    String caseId = openedCase().get("caseId").asString();
    move(caseId, RecoveryState.ASSESSING, 1, null).andExpect(status().isOk());

    ResultActions retry = move(caseId, RecoveryState.ASSESSING, 1, null);

    retry.andExpect(status().isConflict());
    assertThat(code(retry)).isEqualTo("VERSION_CONFLICT");
  }

  @Test
  @DisplayName("Idempotency-Key the same key sent at once: every answer is the one move")
  void parallelRetriesMakeOneMove() throws Exception {
    String caseId = openedCase().get("caseId").asString();
    String key = newKey();
    int attempts = 6;
    ExecutorService pool = Executors.newFixedThreadPool(attempts);
    CountDownLatch start = new CountDownLatch(1);
    List<Future<Integer>> results = new ArrayList<>();
    for (int i = 0; i < attempts; i++) {
      Callable<Integer> attempt =
          () -> {
            start.await();
            return move(caseId, RecoveryState.ASSESSING, 1, key)
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

    assertThat(statuses).allMatch(s -> s == 200);
    assertThat(transitions.findByCaseIdOrderByVersionAfterAsc(UUID.fromString(caseId))).hasSize(2);
  }

  @Test
  @DisplayName("Idempotency-Key a key with unsafe characters is a 400")
  void unsafeKeyIsRefused() throws Exception {
    String caseId = openedCase().get("caseId").asString();

    move(caseId, RecoveryState.ASSESSING, 1, "bad keyé").andExpect(status().isBadRequest());
  }

  // ------------------------------------------------------------------ assignment

  @Test
  @DisplayName(
      "BR-05 assigning a case records who assigned whom, keeps the state and raises the version")
  void assignmentIsRecorded() throws Exception {
    JsonNode opened = openedCase();
    String caseId = opened.get("caseId").asString();

    ResultActions assigned = assign(caseId, "agent-42", 1L, as(Role.DISPATCHER));

    assigned.andExpect(status().isOk());
    assertThat(data(assigned).get("assignedTo").asString()).isEqualTo("agent-42");
    assertThat(data(assigned).get("state").asString()).isEqualTo("OPEN");
    assertThat(data(assigned).get("version").asLong()).isEqualTo(2);
    var last = transitions.findByCaseIdOrderByVersionAfterAsc(UUID.fromString(caseId)).getLast();
    assertThat(last.getFromState()).isEqualTo(RecoveryState.OPEN);
    assertThat(last.getToState()).isEqualTo(RecoveryState.OPEN);
    assertThat(last.getActor()).isEqualTo("user-dispatcher");
    assertThat(last.getReason()).isEqualTo("HANDLER_ASSIGNED: agent-42");
    assertThat(outbox.findByMessageKeyOrderByCreatedAt(caseId)).hasSize(2);
  }

  @Test
  @DisplayName("Assignment assigning the handler who already has the case changes nothing")
  void assigningTheSamePersonAgainIsANoOp() throws Exception {
    String caseId = openedCase().get("caseId").asString();
    assign(caseId, "agent-42", 1L, as(Role.AGENT)).andExpect(status().isOk());

    ResultActions again = assign(caseId, "agent-42", 2L, as(Role.AGENT));

    again.andExpect(status().isOk());
    assertThat(data(again).get("version").asLong()).isEqualTo(2);
    assertThat(transitions.findByCaseIdOrderByVersionAfterAsc(UUID.fromString(caseId))).hasSize(2);
  }

  @Test
  @DisplayName(
      "Assignment a retry whose first answer was lost still carries the old version and succeeds")
  void retryAfterALostAnswerSucceeds() throws Exception {
    String caseId = openedCase().get("caseId").asString();
    assign(caseId, "agent-42", 1L, as(Role.AGENT)).andExpect(status().isOk());

    // The same request again, with the version the first answer would have replaced.
    ResultActions retry = assign(caseId, "agent-42", 1L, as(Role.AGENT));

    retry.andExpect(status().isOk());
    assertThat(data(retry).get("assignedTo").asString()).isEqualTo("agent-42");
    assertThat(data(retry).get("version").asLong()).isEqualTo(2);
    assertThat(transitions.findByCaseIdOrderByVersionAfterAsc(UUID.fromString(caseId))).hasSize(2);
    assertThat(outbox.findByMessageKeyOrderByCreatedAt(caseId)).hasSize(2);
  }

  @Test
  @DisplayName(
      "Assignment a stale caller sees a version conflict even when the case has been closed")
  void staleCallerOnAClosedCaseSeesAVersionConflict() throws Exception {
    String caseId = openedCase().get("caseId").asString();
    move(caseId, RecoveryState.CANCELLED, 1, null).andExpect(status().isOk());

    ResultActions stale = assign(caseId, "agent-42", 1L, as(Role.AGENT));

    stale.andExpect(status().isConflict());
    assertThat(code(stale)).isEqualTo("VERSION_CONFLICT");
  }

  @Test
  @DisplayName("Assignment a case can be handed to another handler, and the history shows both")
  void reassignment() throws Exception {
    String caseId = openedCase().get("caseId").asString();
    assign(caseId, "agent-42", 1L, as(Role.AGENT)).andExpect(status().isOk());

    ResultActions other = assign(caseId, "agent-43", 2L, as(Role.DISPATCHER));

    other.andExpect(status().isOk());
    assertThat(data(other).get("assignedTo").asString()).isEqualTo("agent-43");
    assertThat(data(other).get("version").asLong()).isEqualTo(3);
    assertThat(transitions.findByCaseIdOrderByVersionAfterAsc(UUID.fromString(caseId)))
        .extracting(t -> t.getReason())
        .contains("HANDLER_ASSIGNED: agent-42", "HANDLER_ASSIGNED: agent-43");
  }

  @Test
  @DisplayName("BR-05 after an assignment a move with the old version is a conflict")
  void assignmentRaisesTheVersionForMoves() throws Exception {
    String caseId = openedCase().get("caseId").asString();
    assign(caseId, "agent-42", 1L, as(Role.AGENT)).andExpect(status().isOk());

    move(caseId, RecoveryState.ASSESSING, 1, null).andExpect(status().isConflict());
    move(caseId, RecoveryState.ASSESSING, 2, null).andExpect(status().isOk());
  }

  @Test
  @DisplayName("Assignment a stale version is a 409 VERSION_CONFLICT")
  void staleAssignmentIsAConflict() throws Exception {
    String caseId = openedCase().get("caseId").asString();
    move(caseId, RecoveryState.ASSESSING, 1, null).andExpect(status().isOk());

    ResultActions stale = assign(caseId, "agent-42", 1L, as(Role.AGENT));

    stale.andExpect(status().isConflict());
    assertThat(code(stale)).isEqualTo("VERSION_CONFLICT");
  }

  @Test
  @DisplayName("BR-07 a closed case cannot be assigned")
  void closedCaseCannotBeAssigned() throws Exception {
    String caseId = openedCase().get("caseId").asString();
    move(caseId, RecoveryState.CANCELLED, 1, null).andExpect(status().isOk());

    ResultActions refused = assign(caseId, "agent-42", 2L, as(Role.AGENT));

    refused.andExpect(status().isConflict());
    assertThat(code(refused)).isEqualTo("ILLEGAL_TRANSITION");
  }

  @Test
  @DisplayName("Assignment an unknown case is 404, a bad assignee or a missing version is 400")
  void invalidAssignments() throws Exception {
    String caseId = openedCase().get("caseId").asString();

    assign(UUID.randomUUID().toString(), "agent-42", 1L, as(Role.AGENT))
        .andExpect(status().isNotFound());
    assign(caseId, "bad name!", 1L, as(Role.AGENT)).andExpect(status().isBadRequest());
    assign(caseId, "x".repeat(101), 1L, as(Role.AGENT)).andExpect(status().isBadRequest());
    // An e-mail address is personal data and is not an identity id.
    assign(caseId, "jane.doe@example.com", 1L, as(Role.AGENT)).andExpect(status().isBadRequest());
    assign(caseId, "agent-42", null, as(Role.AGENT)).andExpect(status().isBadRequest());
  }

  @Test
  @DisplayName("AT-16 only agents and dispatchers may assign: admins and passengers get 403")
  void onlyAgentsAndDispatchersMayAssign() throws Exception {
    String caseId = openedCase().get("caseId").asString();

    assign(caseId, "agent-42", 1L, as(Role.ADMIN)).andExpect(status().isForbidden());
    assign(caseId, "agent-42", 1L, as(Role.PASSENGER)).andExpect(status().isForbidden());
    mockMvc
        .perform(
            put(CASES + "/{id}/assignment", caseId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"assignee\":\"agent-42\",\"expectedVersion\":1}"))
        .andExpect(status().isUnauthorized());
    assign(caseId, "agent-42", 1L, as(Role.AGENT)).andExpect(status().isOk());
  }

  @Test
  @DisplayName("Assignment the list shows who has each case")
  void listShowsTheHandler() throws Exception {
    String caseId = openedCase().get("caseId").asString();
    assign(caseId, "agent-77", 1L, as(Role.AGENT)).andExpect(status().isOk());

    ResultActions list = mockMvc.perform(get(CASES).with(as(Role.DISPATCHER)).param("size", "100"));

    list.andExpect(status().isOk());
    assertThat(
            data(list)
                .get("items")
                .valueStream()
                .filter(c -> c.get("caseId").asString().equals(caseId))
                .findFirst()
                .orElseThrow()
                .get("assignedTo")
                .asString())
        .isEqualTo("agent-77");
  }
}
