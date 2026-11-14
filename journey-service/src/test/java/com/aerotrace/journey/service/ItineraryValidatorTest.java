package com.aerotrace.journey.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aerotrace.common.error.ApiException;
import com.aerotrace.common.error.ErrorCode;
import com.aerotrace.journey.config.JourneyProperties;
import com.aerotrace.journey.constant.ApiMessages;
import com.aerotrace.journey.model.dto.request.LegRequest;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class ItineraryValidatorTest {

  private static final Instant T0 = Instant.parse("2026-12-01T08:00:00Z");
  private static final Duration MCT = Duration.ofMinutes(45);

  private final ItineraryValidator validator =
      new ItineraryValidator(
          new JourneyProperties(MCT, Duration.ofMinutes(10), Duration.ofMinutes(5), null, null));

  private static LegRequest leg(String from, String to, Instant departure, Instant arrival) {
    return new LegRequest("TK1234", from, to, departure, arrival);
  }

  private static LegRequest firstLeg() {
    return leg("IST", "FRA", T0, T0.plus(Duration.ofHours(3)));
  }

  @Test
  void acceptsASingleLegAndAConnectionAtExactlyTheMinimum() {
    LegRequest first = firstLeg();
    LegRequest second =
        leg(
            "FRA",
            "LHR",
            first.arrivalAt().plus(MCT),
            first.arrivalAt().plus(MCT).plusSeconds(7200));

    assertThatCode(() -> validator.validate(List.of(first))).doesNotThrowAnyException();
    assertThatCode(() -> validator.validate(List.of(first, second))).doesNotThrowAnyException();
  }

  @Test
  void refusesAConnectionOneSecondShorterThanTheMinimum() {
    LegRequest first = firstLeg();
    Instant departure = first.arrivalAt().plus(MCT).minusSeconds(1);
    LegRequest second = leg("FRA", "LHR", departure, departure.plusSeconds(7200));

    assertRejected(List.of(first, second), ApiMessages.MINIMUM_CONNECTION);
  }

  @Test
  void refusesALegThatLeavesBeforeThePreviousOneArrives() {
    LegRequest first = firstLeg();
    LegRequest second =
        leg("FRA", "LHR", first.arrivalAt().minusSeconds(60), first.arrivalAt().plusSeconds(7200));

    assertRejected(List.of(first, second), ApiMessages.LEG_ORDER);
  }

  @Test
  void refusesLegsThatAreNotChained() {
    LegRequest first = firstLeg();
    LegRequest second =
        leg(
            "CDG",
            "LHR",
            first.arrivalAt().plus(Duration.ofHours(2)),
            first.arrivalAt().plus(Duration.ofHours(4)));

    assertRejected(List.of(first, second), ApiMessages.LEG_CONTINUITY);
  }

  @Test
  void refusesALegThatArrivesBeforeOrWhenItDeparts() {
    assertRejected(List.of(leg("IST", "FRA", T0, T0)), ApiMessages.LEG_TIMES);
    assertRejected(List.of(leg("IST", "FRA", T0, T0.minusSeconds(1))), ApiMessages.LEG_TIMES);
  }

  @Test
  void refusesALegToTheSameAirport() {
    assertRejected(List.of(leg("IST", "IST", T0, T0.plusSeconds(3600))), ApiMessages.LEG_AIRPORTS);
  }

  private void assertRejected(List<LegRequest> legs, String message) {
    assertThatThrownBy(() -> validator.validate(legs))
        .isInstanceOfSatisfying(
            ApiException.class,
            e -> {
              assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR);
              assertThat(e.getMessage()).isEqualTo(message);
            });
  }
}
