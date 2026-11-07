package com.aerotrace.common.model;

/**
 * Recovery case states (spec 5.3). RESOLVED, CLOSED_AS_UNRECOVERABLE and CANCELLED are terminal.
 */
public enum RecoveryState {
  OPEN,
  ASSESSING,
  RESERVED,
  IN_TRANSIT,
  RESOLVED,
  CLOSED_AS_UNRECOVERABLE,
  CANCELLED;

  public boolean isTerminal() {
    return this == RESOLVED || this == CLOSED_AS_UNRECOVERABLE || this == CANCELLED;
  }
}
