package com.aerotrace.recovery.strategy;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * Picks the recovery action: the first strategy, in priority order, that fits the situation.
 *
 * <ol>
 *   <li>{@link Expedited}: there is capacity and enough time to still reach the aircraft;
 *   <li>{@link Reroute}: too little time left to reach it (or it already left);
 *   <li>{@link HoldAtDesk}: the flight is still reachable but there is no capacity, so an agent
 *       decides at the service desk.
 * </ol>
 *
 * The airline policy of the case study is not applied yet: the risk event carries no airline, so
 * every airline uses the default minimum expedite time.
 */
public final class RecoveryActionSelector {

  private final List<RecoveryActionStrategy> strategies;

  public RecoveryActionSelector(List<RecoveryActionStrategy> strategies) {
    this.strategies = List.copyOf(Objects.requireNonNull(strategies, "strategies"));
  }

  /** The default strategies, in priority order. */
  public static RecoveryActionSelector withDefaults(Duration minimumExpediteTime) {
    return new RecoveryActionSelector(
        List.of(
            new Expedited(minimumExpediteTime),
            new Reroute(minimumExpediteTime),
            new HoldAtDesk()));
  }

  public RecoveryAction choose(ActionContext context) {
    return strategies.stream()
        .filter(strategy -> strategy.appliesTo(context))
        .findFirst()
        .map(RecoveryActionStrategy::action)
        .orElse(RecoveryAction.HOLD_AT_SERVICE_DESK);
  }

  /** Expedited transfer: capacity exists and the shortest feasible transfer still fits. */
  record Expedited(Duration minimumExpediteTime) implements RecoveryActionStrategy {
    @Override
    public boolean appliesTo(ActionContext context) {
      return context.capacityAvailable()
          && context.timeToDeparture().compareTo(minimumExpediteTime) >= 0;
    }

    @Override
    public RecoveryAction action() {
      return RecoveryAction.EXPEDITED_TRANSFER;
    }
  }

  /** Reroute: not enough time for any transfer. */
  record Reroute(Duration minimumExpediteTime) implements RecoveryActionStrategy {
    @Override
    public boolean appliesTo(ActionContext context) {
      return context.timeToDeparture().compareTo(minimumExpediteTime) < 0;
    }

    @Override
    public RecoveryAction action() {
      return RecoveryAction.REROUTE_NEXT_FLIGHT;
    }
  }

  /** Hold at the service desk: the safe fallback. */
  record HoldAtDesk() implements RecoveryActionStrategy {
    @Override
    public boolean appliesTo(ActionContext context) {
      return true;
    }

    @Override
    public RecoveryAction action() {
      return RecoveryAction.HOLD_AT_SERVICE_DESK;
    }
  }
}
