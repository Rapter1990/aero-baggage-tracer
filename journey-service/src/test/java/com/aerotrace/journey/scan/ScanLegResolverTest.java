package com.aerotrace.journey.scan;

import static org.assertj.core.api.Assertions.assertThat;

import com.aerotrace.common.model.Checkpoint;
import com.aerotrace.journey.model.entity.ItineraryLegEntity;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ScanLegResolverTest {

  private static final Instant T0 = Instant.parse("2026-11-20T08:00:00Z");

  /** IST to FRA, then FRA to LHR. */
  private static final List<ItineraryLegEntity> IST_FRA_LHR =
      List.of(
          leg(1, "TK1234", "IST", "FRA", T0, T0.plusSeconds(3 * 3600)),
          leg(2, "LH902", "FRA", "LHR", T0.plusSeconds(5 * 3600), T0.plusSeconds(7 * 3600)));

  @Test
  void departureSideCheckpointsBelongToTheLegLeavingTheScanAirport() {
    for (Checkpoint checkpoint :
        List.of(Checkpoint.CHECK_IN, Checkpoint.SORTING, Checkpoint.TRANSFER, Checkpoint.LOADED)) {
      assertThat(flight(IST_FRA_LHR, "IST", checkpoint, T0.minusSeconds(3600)))
          .as(checkpoint.name())
          .contains("TK1234");
      assertThat(flight(IST_FRA_LHR, "FRA", checkpoint, T0.plusSeconds(4 * 3600)))
          .as(checkpoint.name())
          .contains("LH902");
      assertThat(flight(IST_FRA_LHR, "LHR", checkpoint, T0)).as(checkpoint.name()).isEmpty();
    }
  }

  @Test
  void arrivalSideCheckpointsBelongToTheLegLandingAtTheScanAirport() {
    for (Checkpoint checkpoint : List.of(Checkpoint.ARRIVED, Checkpoint.CLAIM)) {
      assertThat(flight(IST_FRA_LHR, "FRA", checkpoint, T0.plusSeconds(3 * 3600)))
          .as(checkpoint.name())
          .contains("TK1234");
      assertThat(flight(IST_FRA_LHR, "LHR", checkpoint, T0.plusSeconds(8 * 3600)))
          .as(checkpoint.name())
          .contains("LH902");
      assertThat(flight(IST_FRA_LHR, "IST", checkpoint, T0)).as(checkpoint.name()).isEmpty();
    }
  }

  @Test
  void anAirportOutsideTheItineraryOrAnEmptyItineraryGivesNoLeg() {
    assertThat(flight(IST_FRA_LHR, "AMS", Checkpoint.SORTING, T0)).isEmpty();
    assertThat(flight(List.of(), "IST", Checkpoint.CHECK_IN, T0)).isEmpty();
  }

  @Test
  void theResolvedLegCarriesItsDepartureTime() {
    Optional<ItineraryLegEntity> leg =
        ScanLegResolver.resolve(IST_FRA_LHR, "FRA", Checkpoint.TRANSFER, T0);
    assertThat(leg).map(ItineraryLegEntity::getDepartureAt).contains(T0.plusSeconds(5 * 3600));
  }

  /** IST to FRA, back to IST, then on to LHR: IST is the origin of two legs. */
  @Test
  void anAirportVisitedTwiceIsResolvedByTheScanTime() {
    Instant d3 = T0.plusSeconds(10 * 3600);
    List<ItineraryLegEntity> roundTrip =
        List.of(
            leg(1, "TK1", "IST", "FRA", T0, T0.plusSeconds(3 * 3600)),
            leg(2, "TK2", "FRA", "IST", T0.plusSeconds(5 * 3600), T0.plusSeconds(8 * 3600)),
            leg(3, "TK3", "IST", "LHR", d3, d3.plusSeconds(4 * 3600)));

    // Before the first departure: the first leg out of IST.
    assertThat(flight(roundTrip, "IST", Checkpoint.LOADED, T0.minusSeconds(600))).contains("TK1");
    // After the first departure, before the third: the next leg out of IST.
    assertThat(flight(roundTrip, "IST", Checkpoint.LOADED, T0.plusSeconds(9 * 3600)))
        .contains("TK3");
    // After every departure (a very late scan): the last leg out of IST.
    assertThat(flight(roundTrip, "IST", Checkpoint.LOADED, d3.plusSeconds(3600))).contains("TK3");
    // Arrival at IST: only leg 2 lands there.
    assertThat(flight(roundTrip, "IST", Checkpoint.ARRIVED, T0.plusSeconds(9 * 3600)))
        .contains("TK2");
  }

  @Test
  void anArrivalScanAtAnAirportReachedTwiceIsResolvedByTheScanTime() {
    Instant d3 = T0.plusSeconds(10 * 3600);
    List<ItineraryLegEntity> twiceToFra =
        List.of(
            leg(1, "TK1", "IST", "FRA", T0, T0.plusSeconds(3 * 3600)),
            leg(2, "TK2", "FRA", "IST", T0.plusSeconds(5 * 3600), T0.plusSeconds(8 * 3600)),
            leg(3, "TK3", "IST", "FRA", d3, d3.plusSeconds(3 * 3600)));

    // Before any arrival (an early scan): the first leg into FRA.
    assertThat(flight(twiceToFra, "FRA", Checkpoint.ARRIVED, T0)).contains("TK1");
    // Between the two arrivals: the leg that has landed most recently.
    assertThat(flight(twiceToFra, "FRA", Checkpoint.CLAIM, T0.plusSeconds(4 * 3600)))
        .contains("TK1");
    // After the second arrival: the second leg into FRA.
    assertThat(flight(twiceToFra, "FRA", Checkpoint.CLAIM, d3.plusSeconds(4 * 3600)))
        .contains("TK3");
  }

  private static Optional<String> flight(
      List<ItineraryLegEntity> legs, String airport, Checkpoint checkpoint, Instant at) {
    return ScanLegResolver.resolve(legs, airport, checkpoint, at)
        .map(ItineraryLegEntity::getFlightNumber);
  }

  private static ItineraryLegEntity leg(
      int no, String flight, String from, String to, Instant departure, Instant arrival) {
    return new ItineraryLegEntity(no, flight, from, to, departure, arrival);
  }
}
