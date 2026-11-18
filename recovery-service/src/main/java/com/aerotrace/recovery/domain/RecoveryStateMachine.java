package com.aerotrace.recovery.domain;

import com.aerotrace.common.error.ApiException;
import com.aerotrace.common.error.ErrorCode;
import com.aerotrace.common.model.RecoveryState;
import com.aerotrace.recovery.constant.RecoveryRules;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The recovery state machine (spec 5.3, BR-05, BR-07). A move is allowed only when every guard
 * accepts it: the current state must not be final, the target must be a legal next state, and a
 * reason must be given (who/when/why is recorded for every move). Cancelling or closing as
 * unrecoverable is possible from every open state; that is an explicit assumption, the case study
 * only says these two are terminal alternatives.
 */
public final class RecoveryStateMachine {

  /** One rule a move must satisfy. Throws an {@link ApiException} when it does not. */
  @FunctionalInterface
  public interface Guard {
    void check(RecoveryState from, RecoveryState to, String reason);
  }

  private static final Map<RecoveryState, Set<RecoveryState>> NEXT =
      new EnumMap<>(RecoveryState.class);

  static {
    NEXT.put(
        RecoveryState.OPEN,
        EnumSet.of(
            RecoveryState.ASSESSING,
            RecoveryState.CANCELLED,
            RecoveryState.CLOSED_AS_UNRECOVERABLE));
    NEXT.put(
        RecoveryState.ASSESSING,
        EnumSet.of(
            RecoveryState.RESERVED,
            RecoveryState.CANCELLED,
            RecoveryState.CLOSED_AS_UNRECOVERABLE));
    NEXT.put(
        RecoveryState.RESERVED,
        EnumSet.of(
            RecoveryState.IN_TRANSIT,
            RecoveryState.CANCELLED,
            RecoveryState.CLOSED_AS_UNRECOVERABLE));
    NEXT.put(
        RecoveryState.IN_TRANSIT,
        EnumSet.of(
            RecoveryState.RESOLVED,
            RecoveryState.CANCELLED,
            RecoveryState.CLOSED_AS_UNRECOVERABLE));
    for (RecoveryState terminal :
        List.of(
            RecoveryState.RESOLVED,
            RecoveryState.CLOSED_AS_UNRECOVERABLE,
            RecoveryState.CANCELLED)) {
      NEXT.put(terminal, EnumSet.noneOf(RecoveryState.class));
    }
  }

  private static final List<Guard> GUARDS =
      List.of(
          RecoveryStateMachine::notFinal,
          RecoveryStateMachine::legalNextState,
          RecoveryStateMachine::reasonGiven);

  private RecoveryStateMachine() {}

  /** The states a case in {@code from} may move to. */
  public static Set<RecoveryState> allowedFrom(RecoveryState from) {
    return Set.copyOf(NEXT.get(from));
  }

  /** Throws unless the move passes every guard. */
  public static void requireAllowed(RecoveryState from, RecoveryState to, String reason) {
    for (Guard guard : GUARDS) {
      guard.check(from, to, reason);
    }
  }

  /** BR-07: a resolved (or otherwise final) case is never reopened. */
  private static void notFinal(RecoveryState from, RecoveryState to, String reason) {
    if (from.isTerminal()) {
      throw new ApiException(ErrorCode.ILLEGAL_TRANSITION, RecoveryRules.CASE_IS_FINAL);
    }
  }

  /** BR-05: only the defined moves. */
  private static void legalNextState(RecoveryState from, RecoveryState to, String reason) {
    if (!NEXT.get(from).contains(to)) {
      throw new ApiException(ErrorCode.ILLEGAL_TRANSITION, RecoveryRules.ILLEGAL_MOVE);
    }
  }

  /** BR-05: every move records why. */
  private static void reasonGiven(RecoveryState from, RecoveryState to, String reason) {
    if (reason == null || reason.isBlank()) {
      throw new ApiException(ErrorCode.VALIDATION_ERROR, RecoveryRules.REASON_REQUIRED);
    }
  }
}
