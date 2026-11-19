package com.aerotrace.capacity.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aerotrace.capacity.base.AbstractCapacityTest;
import com.aerotrace.capacity.model.entity.OutboxEventEntity;
import com.aerotrace.capacity.repository.CapacityResourceRepository;
import com.aerotrace.capacity.repository.OutboxEventRepository;
import com.aerotrace.capacity.service.CapacityService;
import com.aerotrace.common.event.CapacityChangedPayload;
import com.aerotrace.common.event.EventEnvelope;
import com.aerotrace.common.event.EventJson;
import com.aerotrace.common.event.Topics;
import com.aerotrace.common.security.Role;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The capacity-service through the real application, MockMvc and a real PostgreSQL built by
 * Liquibase. The Kafka producer is a mock: events are read from the outbox.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ReservationApiTest extends AbstractCapacityTest {

  private static final String RESERVATIONS = "/api/v1/capacity/reservations";
  private static final String RESOURCES = "/api/v1/capacity/resources";

  @Autowired MockMvc mockMvc;
  @Autowired ObjectMapper json;
  @Autowired CapacityResourceRepository resources;
  @Autowired OutboxEventRepository outbox;
  @Autowired CapacityService service;
  @Autowired MeterRegistry meters;
  @MockitoBean KafkaTemplate<String, String> kafka;

  // ------------------------------------------------------------------ helpers

  private void createResource(String leg, int total) throws Exception {
    mockMvc
        .perform(
            put(RESOURCES + "/{id}", leg)
                .with(as(Role.ADMIN))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"totalSlots\":" + total + "}"))
        .andExpect(status().isOk());
  }

  private static Map<String, Object> body(UUID id, String leg, int slots) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("reservationId", id);
    body.put("caseId", UUID.randomUUID());
    body.put("bagTag", "0123456789");
    body.put("legId", leg);
    body.put("slots", slots);
    return body;
  }

  private ResultActions reserve(Map<String, Object> body, String key, Role role) throws Exception {
    var request =
        post(RESERVATIONS)
            .with(as(role))
            .contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(body));
    if (key != null) {
      request.header("Idempotency-Key", key);
    }
    return mockMvc.perform(request);
  }

  private ResultActions reserve(UUID id, String leg, int slots) throws Exception {
    return reserve(body(id, leg, slots), null, Role.AGENT);
  }

  private int remaining(String leg) {
    return resources.findById(leg).orElseThrow().getRemainingSlots();
  }

  private JsonNode data(ResultActions result) throws Exception {
    return json.readTree(result.andReturn().getResponse().getContentAsString()).get("data");
  }

  private String code(ResultActions result) throws Exception {
    return json.readTree(result.andReturn().getResponse().getContentAsString())
        .get("code")
        .asString();
  }

  /** Fires {@code requests} reservations of one slot at the same moment; returns the statuses. */
  private List<Integer> reserveInParallel(String leg, int requests) throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(requests);
    CountDownLatch go = new CountDownLatch(1);
    try {
      List<Future<Integer>> results = new ArrayList<>();
      for (int i = 0; i < requests; i++) {
        UUID id = UUID.randomUUID();
        results.add(
            pool.submit(
                () -> {
                  go.await();
                  return reserve(id, leg, 1).andReturn().getResponse().getStatus();
                }));
      }
      go.countDown();
      List<Integer> statuses = new ArrayList<>();
      for (Future<Integer> result : results) {
        statuses.add(result.get(60, TimeUnit.SECONDS));
      }
      return statuses;
    } finally {
      pool.shutdownNow();
    }
  }

  // ------------------------------------------------------------------ BR-04 / AT-11

  @Test
  @DisplayName("AT-11 50 concurrent requests for 10 slots: exactly 10 succeed")
  void fiftyParallelRequestsTakeExactlyTenSlots() throws Exception {
    String leg = newLeg();
    createResource(leg, 10);
    double conflictsBefore = meters.counter("capacity.reservation.conflicts").count();

    List<Integer> statuses = reserveInParallel(leg, 50);

    assertThat(statuses.stream().filter(s -> s == 201)).hasSize(10);
    assertThat(statuses.stream().filter(s -> s == 409)).hasSize(40);
    assertThat(remaining(leg)).isZero();
    assertThat(meters.counter("capacity.reservation.conflicts").count() - conflictsBefore)
        .isEqualTo(40);
  }

  @Test
  @DisplayName("BR-04 a request for more slots than are left is refused and takes nothing")
  void moreThanRemainingIsRefused() throws Exception {
    String leg = newLeg();
    createResource(leg, 3);

    ResultActions refused = reserve(UUID.randomUUID(), leg, 4);

    refused.andExpect(status().isConflict());
    assertThat(code(refused)).isEqualTo("CAPACITY_EXCEEDED");
    assertThat(remaining(leg)).isEqualTo(3);
  }

  @Test
  @DisplayName("BR-04 a leg without a resource answers 404 CAPACITY_NOT_PROVISIONED, not 'full'")
  void unknownLegIsNotProvisioned() throws Exception {
    double conflictsBefore = meters.counter("capacity.reservation.conflicts").count();

    ResultActions refused = reserve(UUID.randomUUID(), newLeg(), 1);

    refused.andExpect(status().isNotFound());
    assertThat(code(refused)).isEqualTo("CAPACITY_NOT_PROVISIONED");
    // A set-up problem must not look like capacity running out.
    assertThat(meters.counter("capacity.reservation.conflicts").count()).isEqualTo(conflictsBefore);
  }

  @Test
  @DisplayName(
      "a release that arrives before its hold leaves a tombstone: the late hold is refused")
  void releaseBeforeHoldRefusesTheLateHold() throws Exception {
    String leg = newLeg();
    createResource(leg, 5);
    Map<String, Object> request = body(UUID.randomUUID(), leg, 2);
    UUID id = (UUID) request.get("reservationId");

    // The caller timed out, released by id, and the hold had not arrived yet: 404, as before.
    mockMvc
        .perform(delete(RESERVATIONS + "/{id}", id).with(as(Role.AGENT)))
        .andExpect(status().isNotFound());
    ResultActions late = reserve(request, null, Role.AGENT);

    late.andExpect(status().isConflict());
    assertThat(code(late)).isEqualTo("VERSION_CONFLICT");
    assertThat(remaining(leg)).isEqualTo(5);
    // Releasing again is now a known, closed hold.
    mockMvc
        .perform(delete(RESERVATIONS + "/{id}", id).with(as(Role.AGENT)))
        .andExpect(status().isNoContent());
  }

  @Test
  @DisplayName("parallel release and hold of one id never leave a live hold behind")
  void releaseRacingHoldLeavesNoOrphan() throws Exception {
    for (int round = 0; round < 10; round++) {
      String leg = newLeg();
      createResource(leg, 3);
      Map<String, Object> request = body(UUID.randomUUID(), leg, 1);
      UUID id = (UUID) request.get("reservationId");
      ExecutorService pool = Executors.newFixedThreadPool(2);
      CountDownLatch go = new CountDownLatch(1);
      try {
        Future<Integer> hold =
            pool.submit(
                () -> {
                  go.await();
                  return reserve(request, null, Role.AGENT).andReturn().getResponse().getStatus();
                });
        Future<Integer> release =
            pool.submit(
                () -> {
                  go.await();
                  return mockMvc
                      .perform(delete(RESERVATIONS + "/{id}", id).with(as(Role.AGENT)))
                      .andReturn()
                      .getResponse()
                      .getStatus();
                });
        go.countDown();
        hold.get(60, TimeUnit.SECONDS);
        release.get(60, TimeUnit.SECONDS);
      } finally {
        pool.shutdownNow();
      }

      // Whatever the order, the release is the last word: no slot stays held.
      mockMvc
          .perform(delete(RESERVATIONS + "/{id}", id).with(as(Role.AGENT)))
          .andExpect(status().isNoContent());
      assertThat(remaining(leg)).isEqualTo(3);
    }
  }

  @Test
  @DisplayName("the resource list is paged and capped")
  void resourceListIsPaged() throws Exception {
    createResource(newLeg(), 1);
    createResource(newLeg(), 1);

    mockMvc
        .perform(get(RESOURCES).param("size", "1").with(as(Role.AGENT)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.length()").value(1));
    mockMvc
        .perform(get(RESOURCES + "/{id}", "bad id!").with(as(Role.AGENT)))
        .andExpect(status().isBadRequest());
  }

  // ------------------------------------------------------------------ the contract of the
  // recovery-service

  @Test
  @DisplayName("a hold answers 201 with the reservation id and status HELD")
  void holdAnswers201() throws Exception {
    String leg = newLeg();
    createResource(leg, 5);
    UUID id = UUID.randomUUID();

    ResultActions held = reserve(id, leg, 2);

    held.andExpect(status().isCreated());
    assertThat(data(held).get("reservationId").asString()).isEqualTo(id.toString());
    assertThat(data(held).get("status").asString()).isEqualTo("HELD");
    assertThat(remaining(leg)).isEqualTo(3);
  }

  @Test
  @DisplayName("a repeated request (same reservation id) takes the slots once")
  void repeatedReservationIdTakesSlotsOnce() throws Exception {
    String leg = newLeg();
    createResource(leg, 5);
    Map<String, Object> request = body(UUID.randomUUID(), leg, 2);

    reserve(request, null, Role.AGENT).andExpect(status().isCreated());
    reserve(request, null, Role.AGENT).andExpect(status().isCreated());

    assertThat(remaining(leg)).isEqualTo(3);
  }

  @Test
  @DisplayName("BR-04 parallel retries of one reservation id answer 201 and take the slot once")
  void parallelIdenticalRequestsTakeOneSlot() throws Exception {
    String leg = newLeg();
    createResource(leg, 1);
    Map<String, Object> request = body(UUID.randomUUID(), leg, 1);
    ExecutorService pool = Executors.newFixedThreadPool(10);
    CountDownLatch go = new CountDownLatch(1);
    try {
      List<Future<Integer>> results = new ArrayList<>();
      for (int i = 0; i < 10; i++) {
        results.add(
            pool.submit(
                () -> {
                  go.await();
                  return reserve(request, null, Role.AGENT).andReturn().getResponse().getStatus();
                }));
      }
      go.countDown();
      for (Future<Integer> result : results) {
        // Every retry is told about the one hold, never "full".
        assertThat(result.get(60, TimeUnit.SECONDS)).isEqualTo(201);
      }
    } finally {
      pool.shutdownNow();
    }

    assertThat(remaining(leg)).isZero();
  }

  @Test
  @DisplayName("a reservation id reused for a different request answers 409 VERSION_CONFLICT")
  void reservationIdReusedForAnotherRequest() throws Exception {
    String leg = newLeg();
    createResource(leg, 5);
    UUID id = UUID.randomUUID();
    reserve(id, leg, 1).andExpect(status().isCreated());

    ResultActions reused = reserve(id, leg, 2);

    reused.andExpect(status().isConflict());
    assertThat(code(reused)).isEqualTo("VERSION_CONFLICT");
    assertThat(remaining(leg)).isEqualTo(4);
  }

  @Test
  @DisplayName("the same Idempotency-Key for a new reservation id answers 409 and takes nothing")
  void idempotencyKeyReused() throws Exception {
    String leg = newLeg();
    createResource(leg, 5);
    String key = "key-" + UUID.randomUUID();
    reserve(body(UUID.randomUUID(), leg, 1), key, Role.AGENT).andExpect(status().isCreated());

    ResultActions reused = reserve(body(UUID.randomUUID(), leg, 1), key, Role.AGENT);

    reused.andExpect(status().isConflict());
    assertThat(code(reused)).isEqualTo("VERSION_CONFLICT");
    assertThat(remaining(leg)).isEqualTo(4);
  }

  @Test
  @DisplayName("an invalid request answers 400 and takes nothing")
  void invalidRequest() throws Exception {
    String leg = newLeg();
    createResource(leg, 5);
    Map<String, Object> bad = body(UUID.randomUUID(), leg, 0);

    reserve(bad, null, Role.AGENT).andExpect(status().isBadRequest());
    reserve(body(UUID.randomUUID(), leg, 1), "bad key with spaces", Role.AGENT)
        .andExpect(status().isBadRequest());

    assertThat(remaining(leg)).isEqualTo(5);
  }

  // ------------------------------------------------------------------ release

  @Test
  @DisplayName("releasing a hold answers 204 and returns the slots exactly once")
  void releaseReturnsSlotsOnce() throws Exception {
    String leg = newLeg();
    createResource(leg, 5);
    UUID id = UUID.randomUUID();
    reserve(id, leg, 2).andExpect(status().isCreated());

    mockMvc
        .perform(delete(RESERVATIONS + "/{id}", id).with(as(Role.AGENT)))
        .andExpect(status().isNoContent());
    mockMvc
        .perform(delete(RESERVATIONS + "/{id}", id).with(as(Role.AGENT)))
        .andExpect(status().isNoContent());

    assertThat(remaining(leg)).isEqualTo(5);
  }

  @Test
  @DisplayName("releasing a hold that does not exist answers 404")
  void releaseUnknownAnswers404() throws Exception {
    mockMvc
        .perform(delete(RESERVATIONS + "/{id}", UUID.randomUUID()).with(as(Role.AGENT)))
        .andExpect(status().isNotFound());
  }

  @Test
  @DisplayName("a retry of a hold that was released does not hold again")
  void retryAfterReleaseIsRefused() throws Exception {
    String leg = newLeg();
    createResource(leg, 5);
    Map<String, Object> request = body(UUID.randomUUID(), leg, 1);
    reserve(request, null, Role.AGENT).andExpect(status().isCreated());
    mockMvc
        .perform(delete(RESERVATIONS + "/{id}", request.get("reservationId")).with(as(Role.AGENT)))
        .andExpect(status().isNoContent());

    reserve(request, null, Role.AGENT).andExpect(status().isConflict());

    assertThat(remaining(leg)).isEqualTo(5);
  }

  @Test
  @DisplayName("parallel releases of one hold return its slots once")
  void parallelReleasesReturnSlotsOnce() throws Exception {
    String leg = newLeg();
    createResource(leg, 5);
    UUID id = UUID.randomUUID();
    reserve(id, leg, 3).andExpect(status().isCreated());

    ExecutorService pool = Executors.newFixedThreadPool(10);
    CountDownLatch go = new CountDownLatch(1);
    try {
      List<Future<Integer>> results = new ArrayList<>();
      for (int i = 0; i < 10; i++) {
        results.add(
            pool.submit(
                () -> {
                  go.await();
                  return mockMvc
                      .perform(delete(RESERVATIONS + "/{id}", id).with(as(Role.AGENT)))
                      .andReturn()
                      .getResponse()
                      .getStatus();
                }));
      }
      go.countDown();
      for (Future<Integer> result : results) {
        assertThat(result.get(60, TimeUnit.SECONDS)).isEqualTo(204);
      }
    } finally {
      pool.shutdownNow();
    }

    assertThat(remaining(leg)).isEqualTo(5);
  }

  // ------------------------------------------------------------------ confirmation and expiry

  @Test
  @DisplayName("an unconfirmed hold expires and returns its slots; a confirmed one is never taken")
  void expiryTakesOnlyUnconfirmedHolds() throws Exception {
    String leg = newLeg();
    createResource(leg, 5);
    UUID unconfirmed = UUID.randomUUID();
    UUID confirmed = UUID.randomUUID();
    reserve(unconfirmed, leg, 2).andExpect(status().isCreated());
    reserve(confirmed, leg, 1).andExpect(status().isCreated());
    mockMvc
        .perform(put(RESERVATIONS + "/{id}/confirmation", confirmed).with(as(Role.AGENT)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("CONFIRMED"));

    int expired = service.expireStale();

    assertThat(expired).isGreaterThanOrEqualTo(1);
    assertThat(remaining(leg)).isEqualTo(4);
    // The confirmed hold still releases normally.
    mockMvc
        .perform(delete(RESERVATIONS + "/{id}", confirmed).with(as(Role.AGENT)))
        .andExpect(status().isNoContent());
    assertThat(remaining(leg)).isEqualTo(5);
  }

  @Test
  @DisplayName("confirming a released hold answers 409; an unknown hold answers 404")
  void confirmAfterReleaseOrUnknown() throws Exception {
    String leg = newLeg();
    createResource(leg, 5);
    UUID id = UUID.randomUUID();
    reserve(id, leg, 1).andExpect(status().isCreated());
    mockMvc
        .perform(delete(RESERVATIONS + "/{id}", id).with(as(Role.AGENT)))
        .andExpect(status().isNoContent());

    mockMvc
        .perform(put(RESERVATIONS + "/{id}/confirmation", id).with(as(Role.AGENT)))
        .andExpect(status().isConflict());
    mockMvc
        .perform(put(RESERVATIONS + "/{id}/confirmation", UUID.randomUUID()).with(as(Role.AGENT)))
        .andExpect(status().isNotFound());
  }

  // ------------------------------------------------------------------ resources and events

  @Test
  @DisplayName("an admin sets the total; the free slots move by the difference")
  void adminResizesResource() throws Exception {
    String leg = newLeg();
    createResource(leg, 5);
    reserve(UUID.randomUUID(), leg, 2).andExpect(status().isCreated());

    mockMvc
        .perform(
            put(RESOURCES + "/{id}", leg)
                .with(as(Role.ADMIN))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"totalSlots\":8}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.totalSlots").value(8))
        .andExpect(jsonPath("$.data.remainingSlots").value(6));
    mockMvc
        .perform(get(RESOURCES + "/{id}", leg).with(as(Role.AGENT)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.remainingSlots").value(6));
  }

  @Test
  @DisplayName("a total lower than the slots held answers 409 CAPACITY_EXCEEDED")
  void totalCannotDropBelowHeld() throws Exception {
    String leg = newLeg();
    createResource(leg, 5);
    reserve(UUID.randomUUID(), leg, 4).andExpect(status().isCreated());

    ResultActions refused =
        mockMvc.perform(
            put(RESOURCES + "/{id}", leg)
                .with(as(Role.ADMIN))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"totalSlots\":3}"));

    refused.andExpect(status().isConflict());
    assertThat(code(refused)).isEqualTo("CAPACITY_EXCEEDED");
    assertThat(remaining(leg)).isEqualTo(1);
  }

  @Test
  @DisplayName("an unknown resource answers 404")
  void unknownResource() throws Exception {
    mockMvc
        .perform(get(RESOURCES + "/{id}", newLeg()).with(as(Role.AGENT)))
        .andExpect(status().isNotFound());
  }

  @Test
  @DisplayName("every change of the slots stores recovery.capacity.changed.v1 in the outbox")
  void changesAreStoredAsEvents() throws Exception {
    String leg = newLeg();
    createResource(leg, 5);
    UUID id = UUID.randomUUID();
    reserve(id, leg, 2).andExpect(status().isCreated());
    mockMvc
        .perform(delete(RESERVATIONS + "/{id}", id).with(as(Role.AGENT)))
        .andExpect(status().isNoContent());

    List<OutboxEventEntity> events = outbox.findByMessageKeyOrderByCreatedAt(leg);

    // Created (5), held (3), released (5).
    assertThat(events)
        .hasSize(3)
        .allSatisfy(e -> assertThat(e.getTopic()).isEqualTo(Topics.CAPACITY_CHANGED));
    List<Integer> remainingAfterEach =
        events.stream()
            .map(
                e -> {
                  EventEnvelope<CapacityChangedPayload> envelope =
                      EventJson.read(e.getPayload(), CapacityChangedPayload.class);
                  assertThat(envelope.eventType()).isEqualTo("recovery.capacity.changed");
                  assertThat(envelope.aggregateId()).isEqualTo(leg);
                  assertThat(envelope.payload().totalSlots()).isEqualTo(5);
                  return envelope.payload().remainingSlots();
                })
            .toList();
    assertThat(remainingAfterEach).containsExactly(5, 3, 5);
  }

  @Test
  @DisplayName("a refused reservation stores no event")
  void refusedReservationStoresNoEvent() throws Exception {
    String leg = newLeg();
    createResource(leg, 1);
    reserve(UUID.randomUUID(), leg, 1).andExpect(status().isCreated());
    reserve(UUID.randomUUID(), leg, 1).andExpect(status().isConflict());

    assertThat(outbox.findByMessageKeyOrderByCreatedAt(leg)).hasSize(2);
  }
}
