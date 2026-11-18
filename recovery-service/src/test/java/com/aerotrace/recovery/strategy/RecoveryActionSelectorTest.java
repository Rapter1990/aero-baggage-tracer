package com.aerotrace.recovery.strategy;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class RecoveryActionSelectorTest {

  private final RecoveryActionSelector selector =
      RecoveryActionSelector.withDefaults(Duration.ofMinutes(5));

  @ParameterizedTest(name = "{0} s left, capacity {1} -> {2}")
  @CsvSource({
    // Enough time and capacity: the fastest transfer.
    "900, true, EXPEDITED_TRANSFER",
    // Exactly the minimum still counts as enough time.
    "300, true, EXPEDITED_TRANSFER",
    // One second short of the minimum: no transfer can make it.
    "299, true, REROUTE_NEXT_FLIGHT",
    "0, true, REROUTE_NEXT_FLIGHT",
    // Already departed.
    "-60, true, REROUTE_NEXT_FLIGHT",
    "-60, false, REROUTE_NEXT_FLIGHT",
    // Reachable but no capacity: an agent decides at the desk.
    "900, false, HOLD_AT_SERVICE_DESK",
    "300, false, HOLD_AT_SERVICE_DESK"
  })
  @DisplayName("Strategy the action follows remaining time and capacity")
  void actionFollowsTimeAndCapacity(long secondsLeft, boolean capacity, RecoveryAction expected) {
    assertThat(selector.choose(new ActionContext(Duration.ofSeconds(secondsLeft), capacity)))
        .isEqualTo(expected);
  }

  @Test
  @DisplayName("Strategy the minimum expedite time is configurable")
  void minimumExpediteTimeIsConfigurable() {
    RecoveryActionSelector strict = RecoveryActionSelector.withDefaults(Duration.ofMinutes(15));

    assertThat(strict.choose(new ActionContext(Duration.ofMinutes(10), true)))
        .isEqualTo(RecoveryAction.REROUTE_NEXT_FLIGHT);
    assertThat(selector.choose(new ActionContext(Duration.ofMinutes(10), true)))
        .isEqualTo(RecoveryAction.EXPEDITED_TRANSFER);
  }

  @Test
  @DisplayName("Strategy with no strategy that fits, the safe answer is the service desk")
  void emptySelectorFallsBackToTheDesk() {
    RecoveryActionSelector none = new RecoveryActionSelector(java.util.List.of());

    assertThat(none.choose(new ActionContext(Duration.ofMinutes(10), true)))
        .isEqualTo(RecoveryAction.HOLD_AT_SERVICE_DESK);
  }
}
