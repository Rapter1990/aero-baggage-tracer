package com.aerotrace.recovery.strategy;

/** Strategy pattern: one way of recovering a bag, and the situations it fits. */
public interface RecoveryActionStrategy {

  /** True when this strategy is the right answer for the situation. */
  boolean appliesTo(ActionContext context);

  RecoveryAction action();
}
