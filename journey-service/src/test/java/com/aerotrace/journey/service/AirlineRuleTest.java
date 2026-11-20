package com.aerotrace.journey.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aerotrace.journey.constant.JourneyRules;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The airline of a leg is the carrier prefix of its validated flight number (#28). */
class AirlineRuleTest {

  @Test
  @DisplayName("the airline is the two-character IATA designator the flight number starts with")
  void airlineIsTheCarrierPrefix() {
    assertThat(JourneyRules.airlineOf("TK1981")).isEqualTo("TK");
    assertThat(JourneyRules.airlineOf("LH902")).isEqualTo("LH");
    assertThat(JourneyRules.airlineOf("LH902A")).isEqualTo("LH");
    // Designators may contain a digit.
    assertThat(JourneyRules.airlineOf("4U8")).isEqualTo("4U");
    assertThat(JourneyRules.airlineOf("U21234")).isEqualTo("U2");
  }

  @Test
  @DisplayName("a flight number that breaks the validated format has no airline")
  void onlyValidatedFlightNumbers() {
    for (String invalid : new String[] {null, "", "T", "tk123", "TK", "TK12345", "TK-12"}) {
      assertThatThrownBy(() -> JourneyRules.airlineOf(invalid))
          .as(String.valueOf(invalid))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }
}
