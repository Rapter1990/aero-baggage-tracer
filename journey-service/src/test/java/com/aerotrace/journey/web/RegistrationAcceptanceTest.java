package com.aerotrace.journey.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aerotrace.common.security.Role;
import com.aerotrace.journey.base.AbstractApiTest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

/**
 * Registration: a bag is registered with its itinerary and its journey can be read; a bag tag is
 * unique (409 BAG_TAG_DUPLICATE, also under concurrent registration); IATA codes, leg chronology
 * and the minimum connection time are enforced.
 */
class RegistrationAcceptanceTest extends AbstractApiTest {

  @Test
  void aBagIsRegisteredWithItsItineraryAndHasAnEmptyJourney() throws Exception {
    String passenger = newPassengerRef();
    String bagTag = newBagTag();

    registerBag(registration(bagTag, passenger), as(Role.AGENT))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.data.bagTag").value(bagTag))
        .andExpect(jsonPath("$.data.legs.length()").value(2))
        .andExpect(jsonPath("$.data.legs[0].legNo").value(1))
        .andExpect(jsonPath("$.data.legs[0].origin").value("IST"))
        .andExpect(jsonPath("$.data.legs[1].legNo").value(2))
        .andExpect(jsonPath("$.data.legs[1].destination").value("LHR"));

    journey(bagTag, as(Role.AGENT))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.bagTag").value(bagTag))
        .andExpect(jsonPath("$.data.latestState").doesNotExist())
        .andExpect(jsonPath("$.data.itinerary.length()").value(2))
        .andExpect(jsonPath("$.data.history.length()").value(0));
  }

  @Test
  void aSecondRegistrationOfTheSameTagIsAConflict() throws Exception {
    String bagTag = newBagTag();
    registerBag(registration(bagTag, newPassengerRef()), as(Role.AGENT))
        .andExpect(status().isCreated());

    registerBag(registration(bagTag, newPassengerRef()), as(Role.ADMIN))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("BAG_TAG_DUPLICATE"))
        .andExpect(jsonPath("$.message").value("That bag tag is already registered"));

    assertThat(count("SELECT count(*) FROM journey.baggage WHERE bag_tag = ?", bagTag))
        .isEqualTo(1);
  }

  @Test
  void parallelRegistrationsOfOneTagCreateExactlyOneBag() throws Exception {
    String bagTag = newBagTag();
    int callers = 8;
    List<Callable<Integer>> calls = new ArrayList<>();
    for (int i = 0; i < callers; i++) {
      calls.add(
          () ->
              registerBag(registration(bagTag, newPassengerRef()), as(Role.AGENT))
                  .andReturn()
                  .getResponse()
                  .getStatus());
    }

    List<Integer> statuses = new ArrayList<>();
    try (ExecutorService pool = Executors.newFixedThreadPool(callers)) {
      for (Future<Integer> result : pool.invokeAll(calls)) {
        statuses.add(result.get());
      }
    }

    assertThat(statuses).filteredOn(s -> s == 201).hasSize(1);
    assertThat(statuses).filteredOn(s -> s == 409).hasSize(callers - 1);
    assertThat(count("SELECT count(*) FROM journey.baggage WHERE bag_tag = ?", bagTag))
        .isEqualTo(1);
    assertThat(
            count(
                "SELECT count(*) FROM journey.itinerary_leg l JOIN journey.itinerary i"
                    + " ON i.id = l.itinerary_id JOIN journey.baggage b ON b.id = i.baggage_id"
                    + " WHERE b.bag_tag = ?",
                bagTag))
        .isEqualTo(2);
  }

  @Test
  void formatsOfTagPassengerAirportAndFlightAreChecked() throws Exception {
    Map<String, Object> badTag = registration("12345", newPassengerRef());
    Map<String, Object> badPassenger = registration(newBagTag(), "someone@example.com");
    Map<String, Object> badAirport = registration(newBagTag(), newPassengerRef());
    ((Map<String, Object>) ((List<?>) badAirport.get("legs")).get(0)).put("origin", "Istanbul");
    Map<String, Object> badFlight = registration(newBagTag(), newPassengerRef());
    ((Map<String, Object>) ((List<?>) badFlight.get("legs")).get(0)).put("flightNumber", "??");
    Map<String, Object> noLegs = registration(newBagTag(), newPassengerRef());
    noLegs.put("legs", List.of());

    for (Map<String, Object> body : List.of(badTag, badPassenger, badAirport, badFlight, noLegs)) {
      registerBag(body, as(Role.AGENT))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }
  }

  @Test
  void theErrorNamesTheFieldButNeverEchoesWhatWasSent() throws Exception {
    String sentTag = "tag-with-secret-value";

    String body =
        registerBag(registration(sentTag, newPassengerRef()), as(Role.AGENT))
            .andExpect(status().isBadRequest())
            .andExpect(
                jsonPath("$.validationErrors.bagTag").value("must be a 10-digit IATA bag tag"))
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(body).doesNotContain(sentTag);
  }

  @Test
  void legChronologyAndMinimumConnectionTimeAreEnforced() throws Exception {
    Instant base = Instant.now().plusSeconds(86_400);

    // Not chained: second leg starts at another airport.
    assertRegistrationRefused(
        List.of(
            leg("TK1", "IST", "FRA", base, base.plusSeconds(10_800)),
            leg("LH2", "CDG", "LHR", base.plusSeconds(20_000), base.plusSeconds(27_000))),
        "Each leg must depart from the airport the previous leg arrived at");

    // Second leg departs before the first one arrives.
    assertRegistrationRefused(
        List.of(
            leg("TK1", "IST", "FRA", base, base.plusSeconds(10_800)),
            leg("LH2", "FRA", "LHR", base.plusSeconds(9_000), base.plusSeconds(16_000))),
        "Legs must be in chronological order");

    // Connection of 30 minutes, minimum is 45.
    assertRegistrationRefused(
        List.of(
            leg("TK1", "IST", "FRA", base, base.plusSeconds(10_800)),
            leg("LH2", "FRA", "LHR", base.plusSeconds(10_800 + 1_800), base.plusSeconds(20_000))),
        "A connection is shorter than the minimum connection time");

    // A leg that arrives before it departs.
    assertRegistrationRefused(
        List.of(leg("TK1", "IST", "FRA", base, base.minusSeconds(60))),
        "A leg must arrive after it departs");
  }

  private void assertRegistrationRefused(List<Map<String, Object>> legs, String message)
      throws Exception {
    Map<String, Object> body = registration(newBagTag(), newPassengerRef());
    body.put("legs", legs);

    registerBag(body, as(Role.AGENT))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
        .andExpect(jsonPath("$.message").value(message));
    assertThat(count("SELECT count(*) FROM journey.baggage WHERE bag_tag = ?", body.get("bagTag")))
        .isZero();
  }
}
