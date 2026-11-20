package com.aerotrace.journey.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aerotrace.common.correlation.CorrelationId;
import com.aerotrace.common.event.BagRegisteredPayload;
import com.aerotrace.common.event.EventEnvelope;
import com.aerotrace.common.event.EventJson;
import com.aerotrace.common.event.LegSnapshot;
import com.aerotrace.common.event.Topics;
import com.aerotrace.common.security.Role;
import com.aerotrace.journey.base.AbstractApiTest;
import com.aerotrace.journey.model.entity.OutboxEventEntity;
import com.aerotrace.journey.repository.OutboxEventRepository;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@code baggage.bag.registered.v1} (#28): registering a bag queues exactly one event in the
 * transactional outbox, keyed by the bag tag, with the snapshot of every leg (airline included).
 */
class BagRegisteredEventTest extends AbstractApiTest {

  @Autowired OutboxEventRepository outbox;

  private List<OutboxEventEntity> registrations(String bagTag) {
    return outbox.findByMessageKeyOrderByCreatedAt(bagTag).stream()
        .filter(e -> Topics.BAG_REGISTERED.equals(e.getTopic()))
        .toList();
  }

  private Object legColumn(String bagTag, int legNo, String column) {
    return new TransactionTemplate(transactionManager)
        .execute(
            status ->
                entityManager
                    .createNativeQuery(
                        "SELECT "
                            + column
                            + " FROM journey.itinerary_leg l"
                            + " JOIN journey.itinerary i ON i.id = l.itinerary_id"
                            + " JOIN journey.baggage b ON b.id = i.baggage_id"
                            + " WHERE b.bag_tag = ?1 AND l.leg_no = ?2")
                    .setParameter(1, bagTag)
                    .setParameter(2, legNo)
                    .getSingleResult());
  }

  private static Instant instant(Object value) {
    return value instanceof Instant i
        ? i
        : value instanceof OffsetDateTime odt
            ? odt.toInstant()
            : ((java.sql.Timestamp) value).toInstant();
  }

  @Test
  @DisplayName(
      "registering a bag queues baggage.bag.registered.v1 once, keyed by bagTag, with the snapshot"
          + " of every leg and the request's correlationId")
  void registrationQueuesOneEvent() throws Exception {
    String bagTag = newBagTag();
    String correlation = "corr-reg-" + UUID.randomUUID().toString().substring(0, 8);

    mockMvc
        .perform(
            post("/api/v1/bags")
                .with(as(Role.AGENT))
                .header(CorrelationId.HEADER, correlation)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(registration(bagTag, newPassengerRef()))))
        .andExpect(status().isCreated());

    List<OutboxEventEntity> rows = registrations(bagTag);
    assertThat(rows).hasSize(1);
    OutboxEventEntity row = rows.getFirst();
    assertThat(row.getTopic()).isEqualTo("baggage.bag.registered.v1");
    assertThat(row.getMessageKey()).isEqualTo(bagTag);
    assertThat(row.getPublishedAt()).isNull();

    EventEnvelope<BagRegisteredPayload> event =
        EventJson.read(row.getPayload(), BagRegisteredPayload.class);
    assertThat(event.eventType()).isEqualTo("baggage.bag.registered");
    assertThat(event.schemaVersion()).isEqualTo(1);
    assertThat(event.aggregateId()).isEqualTo(bagTag);
    assertThat(event.correlationId()).isEqualTo(correlation);
    assertThat(event.payload().bagTag()).isEqualTo(bagTag);
    assertThat(event.payload().legs())
        .containsExactly(
            new LegSnapshot(
                legColumn(bagTag, 1, "l.id").toString(),
                "TK1234",
                "TK",
                ISTANBUL,
                FRANKFURT,
                instant(legColumn(bagTag, 1, "l.departure_at"))),
            new LegSnapshot(
                legColumn(bagTag, 2, "l.id").toString(),
                "LH902",
                "LH",
                FRANKFURT,
                LONDON,
                instant(legColumn(bagTag, 2, "l.departure_at"))));
    // No passenger data in the event: the payload has the bag and its legs only.
    assertThat(row.getPayload()).doesNotContain("passengerRef").doesNotContain("P-");
  }

  @Test
  @DisplayName("a refused second registration of the tag queues no second event")
  void duplicateRegistrationQueuesNothing() throws Exception {
    String bagTag = newBagTag();
    registerBag(registration(bagTag, newPassengerRef()), as(Role.AGENT))
        .andExpect(status().isCreated());

    registerBag(registration(bagTag, newPassengerRef()), as(Role.ADMIN))
        .andExpect(status().isConflict());

    assertThat(registrations(bagTag)).hasSize(1);
  }

  @Test
  @DisplayName("parallel registrations of one tag queue exactly one event")
  void parallelRegistrationsQueueOneEvent() throws Exception {
    String bagTag = newBagTag();
    Map<String, Object> body = registration(bagTag, newPassengerRef());
    List<Callable<Integer>> calls = new ArrayList<>();
    for (int i = 0; i < 6; i++) {
      calls.add(() -> registerBag(body, as(Role.AGENT)).andReturn().getResponse().getStatus());
    }
    try (ExecutorService pool = Executors.newFixedThreadPool(6)) {
      pool.invokeAll(calls);
    }

    assertThat(registrations(bagTag)).hasSize(1);
  }

  @Test
  @DisplayName("an invalid registration is refused and queues nothing")
  void invalidRegistrationQueuesNothing() throws Exception {
    String bagTag = newBagTag();
    Map<String, Object> body = registration(bagTag, newPassengerRef());
    Instant base = Instant.now().plusSeconds(86_400);
    // The second leg does not start where the first ended.
    body.put(
        "legs",
        List.of(
            leg("TK1234", ISTANBUL, FRANKFURT, base, base.plusSeconds(3 * 3600)),
            leg("LH902", LONDON, "CDG", base.plusSeconds(5 * 3600), base.plusSeconds(7 * 3600))));

    registerBag(body, as(Role.AGENT)).andExpect(status().isBadRequest());

    assertThat(registrations(bagTag)).isEmpty();
  }
}
