package com.aerotrace.recovery.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aerotrace.common.event.CaseChangedPayload;
import com.aerotrace.common.event.EventEnvelope;
import com.aerotrace.common.event.EventJson;
import com.aerotrace.common.model.RecoveryState;
import com.aerotrace.common.security.JwtClaims;
import com.aerotrace.common.security.Role;
import com.aerotrace.recovery.base.AbstractTestContainerConfiguration;
import com.aerotrace.recovery.outbox.OutboxRelay;
import com.aerotrace.recovery.repository.OutboxEventRepository;
import com.aerotrace.recovery.repository.StateTransitionRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
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
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The recovery cases through the real application, MockMvc and a real PostgreSQL built by
 * Liquibase. The Kafka producer is a mock: the relay is driven by hand.
 */
@SpringBootTest
@AutoConfigureMockMvc
class RecoveryCaseApiTest extends AbstractTestContainerConfiguration {

  private static final String CASES = "/api/v1/recovery-cases";

  @Autowired MockMvc mockMvc;
  @Autowired ObjectMapper json;
  @Autowired OutboxEventRepository outbox;
  @Autowired StateTransitionRepository transitions;
  @Autowired OutboxRelay relay;
  @MockitoBean KafkaTemplate<String, String> kafka;

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

  private static String newBagTag() {
    return String.format("%010d", ThreadLocalRandom.current().nextLong(10_000_000_000L));
  }

  private static Map<String, Object> caseBody(String bagTag, String legId) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("bagTag", bagTag);
    body.put("legId", legId);
    body.put("reason", "HIGH_CONNECTION_RISK");
    return body;
  }

  private ResultActions open(String bagTag, String legId, String key, RequestPostProcessor caller)
      throws Exception {
    var request =
        post(CASES)
            .with(caller)
            .contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(caseBody(bagTag, legId)));
    if (key != null) {
      request.header("Idempotency-Key", key);
    }
    return mockMvc.perform(request);
  }

  private ResultActions move(
      String caseId,
      RecoveryState target,
      long expectedVersion,
      String reason,
      RequestPostProcessor caller)
      throws Exception {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("targetState", target.name());
    body.put("expectedVersion", expectedVersion);
    body.put("reason", reason);
    return mockMvc.perform(
        patch(CASES + "/{id}/transitions", caseId)
            .with(caller)
            .contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(body)));
  }

  private JsonNode body(ResultActions result) throws Exception {
    return json.readTree(result.andReturn().getResponse().getContentAsString());
  }

  private JsonNode data(ResultActions result) throws Exception {
    return body(result).get("data");
  }

  /** Opens a case as an agent and returns its data node. */
  private JsonNode openedCase() throws Exception {
    ResultActions created = open(newBagTag(), "LEG-2", null, as(Role.AGENT));
    created.andExpect(status().isCreated());
    return data(created);
  }

  private JsonNode advance(JsonNode current, RecoveryState target) throws Exception {
    ResultActions moved =
        move(
            current.get("caseId").asString(),
            target,
            current.get("version").asLong(),
            "Moving to " + target,
            as(Role.DISPATCHER));
    moved.andExpect(status().isOk());
    return data(moved);
  }

  // ------------------------------------------------------------------ opening a case

  @Test
  @DisplayName("BR-01 a new case starts OPEN at version 1")
  void newCaseStartsOpenAtVersionOne() throws Exception {
    ResultActions created = open(newBagTag(), "LEG-2", null, as(Role.AGENT));

    created.andExpect(status().isCreated());
    JsonNode data = data(created);
    assertThat(data.get("state").asString()).isEqualTo("OPEN");
    assertThat(data.get("version").asLong()).isEqualTo(1);
    assertThat(UUID.fromString(data.get("caseId").asString())).isNotNull();
  }

  @Test
  @DisplayName("AT-09 two parallel attempts for the same bag and leg create exactly one case")
  void parallelAttemptsCreateOneCase() throws Exception {
    String bagTag = newBagTag();
    int attempts = 12;
    ExecutorService pool = Executors.newFixedThreadPool(attempts);
    CountDownLatch start = new CountDownLatch(1);
    List<Future<Integer>> results = new ArrayList<>();
    for (int i = 0; i < attempts; i++) {
      Callable<Integer> attempt =
          () -> {
            start.await();
            return open(bagTag, "LEG-2", null, as(Role.AGENT))
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

    assertThat(statuses.stream().filter(s -> s == 201)).hasSize(1);
    assertThat(statuses.stream().filter(s -> s == 409)).hasSize(attempts - 1);
    ResultActions list = mockMvc.perform(get(CASES).with(as(Role.AGENT)).param("size", "100"));
    long forBag =
        body(list)
            .get("data")
            .get("items")
            .valueStream()
            .filter(c -> c.get("bagTag").asString().equals(bagTag))
            .count();
    assertThat(forBag).isEqualTo(1);
  }

  @Test
  @DisplayName("BR-01 the second attempt gets the stable 409 DUPLICATE_RECOVERY_CASE contract")
  void secondAttemptGetsDuplicateContract() throws Exception {
    String bagTag = newBagTag();
    open(bagTag, "LEG-2", null, as(Role.AGENT)).andExpect(status().isCreated());

    ResultActions second = open(bagTag, "LEG-2", null, as(Role.DISPATCHER));

    second.andExpect(status().isConflict());
    assertThat(body(second).get("code").asString()).isEqualTo("DUPLICATE_RECOVERY_CASE");
  }

  @Test
  @DisplayName("BR-01 another leg of the same bag is a different case")
  void anotherLegIsAnotherCase() throws Exception {
    String bagTag = newBagTag();
    open(bagTag, "LEG-1", null, as(Role.AGENT)).andExpect(status().isCreated());

    open(bagTag, "LEG-2", null, as(Role.AGENT)).andExpect(status().isCreated());
  }

  @Test
  @DisplayName("BR-07 once a case is final the leg is free for a new case with a new id")
  void finalCaseFreesTheLegForANewCase() throws Exception {
    String bagTag = newBagTag();
    JsonNode first = data(open(bagTag, "LEG-2", null, as(Role.AGENT)));
    advance(first, RecoveryState.CANCELLED);

    ResultActions again = open(bagTag, "LEG-2", null, as(Role.AGENT));

    again.andExpect(status().isCreated());
    assertThat(data(again).get("caseId").asString()).isNotEqualTo(first.get("caseId").asString());
  }

  @Test
  @DisplayName("Idempotency-Key a retry returns the original case and creates nothing new")
  void retryWithSameKeyReturnsOriginalCase() throws Exception {
    String bagTag = newBagTag();
    String key = "key-" + UUID.randomUUID();
    JsonNode first = data(open(bagTag, "LEG-2", key, as(Role.AGENT)));
    int eventsBefore =
        outbox.findByMessageKeyOrderByCreatedAt(first.get("caseId").asString()).size();

    ResultActions retry = open(bagTag, "LEG-2", key, as(Role.AGENT));

    retry.andExpect(status().isCreated());
    assertThat(data(retry).get("caseId").asString()).isEqualTo(first.get("caseId").asString());
    assertThat(outbox.findByMessageKeyOrderByCreatedAt(first.get("caseId").asString()))
        .hasSize(eventsBefore);
  }

  @Test
  @DisplayName("Idempotency-Key reused for a different request is a conflict")
  void keyReusedForAnotherRequestIsAConflict() throws Exception {
    String key = "key-" + UUID.randomUUID();
    open(newBagTag(), "LEG-2", key, as(Role.AGENT)).andExpect(status().isCreated());

    ResultActions other = open(newBagTag(), "LEG-2", key, as(Role.AGENT));

    other.andExpect(status().isConflict());
    assertThat(body(other).get("code").asString()).isEqualTo("VERSION_CONFLICT");
  }

  @Test
  @DisplayName("Idempotency-Key with unsafe characters is refused")
  void unsafeKeyIsRefused() throws Exception {
    open(newBagTag(), "LEG-2", "bad keyé", as(Role.AGENT)).andExpect(status().isBadRequest());
  }

  @Test
  @DisplayName("Validation a bad bag tag is a 400")
  void badBagTagIsRefused() throws Exception {
    ResultActions bad = open("AB12", "LEG-2", null, as(Role.AGENT));

    bad.andExpect(status().isBadRequest());
    assertThat(body(bad).get("code").asString()).isEqualTo("VALIDATION_ERROR");
  }

  // ------------------------------------------------------------------ state machine

  @Test
  @DisplayName("BR-05 each move records who, when and why, and raises the version")
  void everyMoveIsRecorded() throws Exception {
    JsonNode current = openedCase();
    UUID caseId = UUID.fromString(current.get("caseId").asString());

    current = advance(current, RecoveryState.ASSESSING);
    current = advance(current, RecoveryState.RESERVED);
    current = advance(current, RecoveryState.IN_TRANSIT);
    current = advance(current, RecoveryState.RESOLVED);

    assertThat(current.get("state").asString()).isEqualTo("RESOLVED");
    assertThat(current.get("version").asLong()).isEqualTo(5);
    var history = transitions.findByCaseIdOrderByVersionAfterAsc(caseId);
    assertThat(history).hasSize(5);
    assertThat(history.get(0).getFromState()).isNull();
    assertThat(history.get(0).getToState()).isEqualTo(RecoveryState.OPEN);
    assertThat(history.get(4).getFromState()).isEqualTo(RecoveryState.IN_TRANSIT);
    assertThat(history.get(4).getToState()).isEqualTo(RecoveryState.RESOLVED);
    assertThat(history)
        .allSatisfy(
            t -> {
              assertThat(t.getActor()).isNotBlank();
              assertThat(t.getOccurredAt()).isNotNull();
              assertThat(t.getReason()).isNotBlank();
            });
    assertThat(history.get(4).getActor()).isEqualTo("user-dispatcher");
  }

  @Test
  @DisplayName("AT-10 RESOLVED to IN_TRANSIT is a 409 ILLEGAL_TRANSITION and changes nothing")
  void resolvedCannotMoveBackToInTransit() throws Exception {
    JsonNode current = openedCase();
    for (RecoveryState next :
        List.of(
            RecoveryState.ASSESSING,
            RecoveryState.RESERVED,
            RecoveryState.IN_TRANSIT,
            RecoveryState.RESOLVED)) {
      current = advance(current, next);
    }

    ResultActions illegal =
        move(
            current.get("caseId").asString(),
            RecoveryState.IN_TRANSIT,
            current.get("version").asLong(),
            "Trying to reopen",
            as(Role.AGENT));

    illegal.andExpect(status().isConflict());
    assertThat(body(illegal).get("code").asString()).isEqualTo("ILLEGAL_TRANSITION");
    var history =
        transitions.findByCaseIdOrderByVersionAfterAsc(
            UUID.fromString(current.get("caseId").asString()));
    assertThat(history).hasSize(5);
  }

  @Test
  @DisplayName("BR-07 a resolved case cannot be reopened to any state")
  void resolvedCaseCannotBeReopened() throws Exception {
    JsonNode current = openedCase();
    for (RecoveryState next :
        List.of(
            RecoveryState.ASSESSING,
            RecoveryState.RESERVED,
            RecoveryState.IN_TRANSIT,
            RecoveryState.RESOLVED)) {
      current = advance(current, next);
    }

    for (RecoveryState target : List.of(RecoveryState.OPEN, RecoveryState.ASSESSING)) {
      ResultActions reopen =
          move(
              current.get("caseId").asString(),
              target,
              current.get("version").asLong(),
              "Reopen",
              as(Role.AGENT));
      reopen.andExpect(status().isConflict());
      assertThat(body(reopen).get("code").asString()).isEqualTo("ILLEGAL_TRANSITION");
    }
  }

  @Test
  @DisplayName("BR-05 a stale expectedVersion is a 409 VERSION_CONFLICT")
  void staleVersionIsAConflict() throws Exception {
    JsonNode current = openedCase();
    advance(current, RecoveryState.ASSESSING);

    ResultActions stale =
        move(
            current.get("caseId").asString(),
            RecoveryState.CANCELLED,
            current.get("version").asLong(),
            "Late",
            as(Role.AGENT));

    stale.andExpect(status().isConflict());
    assertThat(body(stale).get("code").asString()).isEqualTo("VERSION_CONFLICT");
  }

  @Test
  @DisplayName("BR-05 concurrent moves from the same version: exactly one wins")
  void concurrentMovesOnlyOneWins() throws Exception {
    JsonNode current = openedCase();
    String caseId = current.get("caseId").asString();
    int attempts = 8;
    ExecutorService pool = Executors.newFixedThreadPool(attempts);
    CountDownLatch start = new CountDownLatch(1);
    List<Future<Integer>> results = new ArrayList<>();
    for (int i = 0; i < attempts; i++) {
      Callable<Integer> attempt =
          () -> {
            start.await();
            return move(caseId, RecoveryState.ASSESSING, 1, "Race", as(Role.AGENT))
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
    assertThat(transitions.findByCaseIdOrderByVersionAfterAsc(UUID.fromString(caseId))).hasSize(2);
  }

  @Test
  @DisplayName("BR-05 a move without a reason is a 400")
  void moveWithoutReasonIsRefused() throws Exception {
    JsonNode current = openedCase();

    ResultActions bad =
        move(
            current.get("caseId").asString(),
            RecoveryState.ASSESSING,
            current.get("version").asLong(),
            "",
            as(Role.AGENT));

    bad.andExpect(status().isBadRequest());
  }

  @Test
  @DisplayName("Unknown case: a move answers 404")
  void unknownCaseIsNotFound() throws Exception {
    move(UUID.randomUUID().toString(), RecoveryState.ASSESSING, 1, "Why", as(Role.AGENT))
        .andExpect(status().isNotFound());
  }

  // ------------------------------------------------------------------ listing

  @Test
  @DisplayName("List filters by state and pages deterministically")
  void listFiltersAndPages() throws Exception {
    JsonNode a = openedCase();
    openedCase();
    advance(a, RecoveryState.ASSESSING);

    ResultActions open =
        mockMvc.perform(get(CASES).with(as(Role.DISPATCHER)).param("state", "OPEN"));
    ResultActions firstPage =
        mockMvc.perform(get(CASES).with(as(Role.DISPATCHER)).param("size", "1").param("page", "0"));
    ResultActions again =
        mockMvc.perform(get(CASES).with(as(Role.DISPATCHER)).param("size", "1").param("page", "0"));

    open.andExpect(status().isOk());
    assertThat(data(open).get("items").valueStream())
        .allSatisfy(c -> assertThat(c.get("state").asString()).isEqualTo("OPEN"));
    assertThat(data(firstPage).get("items")).hasSize(1);
    assertThat(data(firstPage).get("items").get(0).get("caseId").asString())
        .isEqualTo(data(again).get("items").get(0).get("caseId").asString());
    assertThat(data(firstPage).get("totalElements").asLong()).isGreaterThanOrEqualTo(2);
  }

  @Test
  @DisplayName("List caps the page size at 100")
  void listCapsPageSize() throws Exception {
    ResultActions list = mockMvc.perform(get(CASES).with(as(Role.AGENT)).param("size", "5000"));

    list.andExpect(status().isOk());
    assertThat(data(list).get("size").asInt()).isEqualTo(100);
  }

  // ------------------------------------------------------------------ security

  @Test
  @DisplayName("AT-16 no token is 401")
  void noTokenIs401() throws Exception {
    mockMvc
        .perform(
            post(CASES)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(caseBody(newBagTag(), "LEG-2"))))
        .andExpect(status().isUnauthorized());
  }

  @Test
  @DisplayName("AT-16 a passenger may not open, move or list cases (403)")
  void passengerIsForbidden() throws Exception {
    open(newBagTag(), "LEG-2", null, as(Role.PASSENGER)).andExpect(status().isForbidden());
    move(UUID.randomUUID().toString(), RecoveryState.ASSESSING, 1, "Why", as(Role.PASSENGER))
        .andExpect(status().isForbidden());
    mockMvc.perform(get(CASES).with(as(Role.PASSENGER))).andExpect(status().isForbidden());
  }

  @Test
  @DisplayName("AT-16 an admin may open and list but not move a case (403)")
  void adminMayNotMoveACase() throws Exception {
    JsonNode current = openedCase();

    move(
            current.get("caseId").asString(),
            RecoveryState.ASSESSING,
            current.get("version").asLong(),
            "Why",
            as(Role.ADMIN))
        .andExpect(status().isForbidden());
    open(newBagTag(), "LEG-9", null, as(Role.ADMIN)).andExpect(status().isCreated());
    mockMvc.perform(get(CASES).with(as(Role.ADMIN))).andExpect(status().isOk());
  }

  // ------------------------------------------------------------------ outbox (case level of AT-14)

  @Test
  @DisplayName("AT-14 the case change and its event are stored together; a failed publish keeps it")
  void eventIsStoredWithTheChangeAndSurvivesAFailedPublish() throws Exception {
    JsonNode current = openedCase();
    String caseId = current.get("caseId").asString();
    advance(current, RecoveryState.ASSESSING);
    // Kafka is down: the send fails.
    when(kafka.send(anyString(), anyString(), anyString()))
        .thenReturn(
            CompletableFuture.failedFuture(
                new org.apache.kafka.common.errors.TimeoutException("broker down")));

    relay.publishPending();
    var pending = outbox.findByMessageKeyOrderByCreatedAt(caseId);

    assertThat(pending).hasSize(2);
    assertThat(pending).allSatisfy(e -> assertThat(e.getPublishedAt()).isNull());

    // Kafka is back: the committed events go out, in order.
    when(kafka.send(anyString(), anyString(), anyString()))
        .thenReturn(CompletableFuture.completedFuture(org.mockito.Mockito.mock(SendResult.class)));
    // The database is shared with other tests, so drain every batch.
    while (relay.publishPending() > 0) {
      // keep going until nothing is left
    }
    var published = outbox.findByMessageKeyOrderByCreatedAt(caseId);

    assertThat(published).allSatisfy(e -> assertThat(e.getPublishedAt()).isNotNull());
    EventEnvelope<CaseChangedPayload> last =
        EventJson.read(published.get(1).getPayload(), CaseChangedPayload.class);
    assertThat(last.payload().state()).isEqualTo(RecoveryState.ASSESSING);
    assertThat(last.payload().version()).isEqualTo(2);
    assertThat(last.aggregateId()).isEqualTo(caseId);
    assertThat(published.get(1).getTopic()).isEqualTo("recovery.case.changed.v1");
  }

  @Test
  @DisplayName(
      "AT-14 publishing the same row twice is not possible: a published row is not claimed again")
  void publishedRowsAreNotSentAgain() throws Exception {
    JsonNode current = openedCase();
    when(kafka.send(anyString(), anyString(), anyString()))
        .thenReturn(CompletableFuture.completedFuture(org.mockito.Mockito.mock(SendResult.class)));
    while (relay.publishPending() > 0) {
      // drain every batch
    }
    long publishedBefore =
        outbox.findByMessageKeyOrderByCreatedAt(current.get("caseId").asString()).stream()
            .filter(e -> e.getPublishedAt() != null)
            .count();

    relay.publishPending();

    assertThat(
            outbox.findByMessageKeyOrderByCreatedAt(current.get("caseId").asString()).stream()
                .filter(e -> e.getPublishedAt() != null)
                .count())
        .isEqualTo(publishedBefore);
  }
}
