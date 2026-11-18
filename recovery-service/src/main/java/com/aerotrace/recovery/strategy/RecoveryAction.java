package com.aerotrace.recovery.strategy;

/** What recovery should do for a bag at risk of missing its connection (spec 5.3). */
public enum RecoveryAction {
  /** Move the bag to the aircraft by the fastest transport that still reaches it. */
  EXPEDITED_TRANSFER,
  /** The flight cannot be caught: send the bag on the next flight. */
  REROUTE_NEXT_FLIGHT,
  /** Keep the bag at the service desk until an agent decides. */
  HOLD_AT_SERVICE_DESK
}
