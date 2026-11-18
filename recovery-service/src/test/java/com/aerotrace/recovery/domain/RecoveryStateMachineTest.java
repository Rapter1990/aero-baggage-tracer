package com.aerotrace.recovery.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aerotrace.common.error.ApiException;
import com.aerotrace.common.error.ErrorCode;
import com.aerotrace.common.model.RecoveryState;
import java.util.EnumSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class RecoveryStateMachineTest {

  private static final String WHY = "Transport slot secured";

  @Test
  @DisplayName("BR-05 the happy path OPEN to RESOLVED is allowed step by step")
  void happyPathIsAllowed() {
    assertThatCode(
            () -> {
              RecoveryStateMachine.requireAllowed(RecoveryState.OPEN, RecoveryState.ASSESSING, WHY);
              RecoveryStateMachine.requireAllowed(
                  RecoveryState.ASSESSING, RecoveryState.RESERVED, WHY);
              RecoveryStateMachine.requireAllowed(
                  RecoveryState.RESERVED, RecoveryState.IN_TRANSIT, WHY);
              RecoveryStateMachine.requireAllowed(
                  RecoveryState.IN_TRANSIT, RecoveryState.RESOLVED, WHY);
            })
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("BR-05 a state cannot be skipped")
  void skippingAStateIsIllegal() {
    assertThatThrownBy(
            () ->
                RecoveryStateMachine.requireAllowed(
                    RecoveryState.OPEN, RecoveryState.RESERVED, WHY))
        .isInstanceOfSatisfying(
            ApiException.class,
            e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.ILLEGAL_TRANSITION));
  }

  @Test
  @DisplayName("BR-05 a case cannot move backwards")
  void movingBackwardsIsIllegal() {
    assertThatThrownBy(
            () ->
                RecoveryStateMachine.requireAllowed(
                    RecoveryState.RESERVED, RecoveryState.ASSESSING, WHY))
        .isInstanceOfSatisfying(
            ApiException.class,
            e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.ILLEGAL_TRANSITION));
  }

  @ParameterizedTest(name = "BR-05 {0} can be cancelled or closed as unrecoverable")
  @EnumSource(
      value = RecoveryState.class,
      names = {"OPEN", "ASSESSING", "RESERVED", "IN_TRANSIT"})
  @DisplayName("BR-05 every open state can end in a terminal alternative")
  void everyOpenStateCanBeCancelledOrClosed(RecoveryState from) {
    assertThat(RecoveryStateMachine.allowedFrom(from))
        .contains(RecoveryState.CANCELLED, RecoveryState.CLOSED_AS_UNRECOVERABLE);
  }

  @ParameterizedTest(name = "BR-07 {0} is final: no move out of it")
  @EnumSource(RecoveryState.class)
  @DisplayName("BR-07 a final case can never be reopened")
  void finalStatesNeverMove(RecoveryState from) {
    if (!from.isTerminal()) {
      assertThat(RecoveryStateMachine.allowedFrom(from)).isNotEmpty();
      return;
    }
    assertThat(RecoveryStateMachine.allowedFrom(from)).isEmpty();
    for (RecoveryState to : EnumSet.allOf(RecoveryState.class)) {
      assertThatThrownBy(() -> RecoveryStateMachine.requireAllowed(from, to, WHY))
          .isInstanceOfSatisfying(
              ApiException.class,
              e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.ILLEGAL_TRANSITION));
    }
  }

  @Test
  @DisplayName("AT-10 RESOLVED to IN_TRANSIT is an illegal transition")
  void resolvedToInTransitIsIllegal() {
    assertThatThrownBy(
            () ->
                RecoveryStateMachine.requireAllowed(
                    RecoveryState.RESOLVED, RecoveryState.IN_TRANSIT, WHY))
        .isInstanceOfSatisfying(
            ApiException.class,
            e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.ILLEGAL_TRANSITION));
  }

  @ParameterizedTest(name = "BR-05 a move with reason \"{0}\" is refused")
  @org.junit.jupiter.params.provider.NullAndEmptySource
  @org.junit.jupiter.params.provider.ValueSource(strings = {"  "})
  @DisplayName("BR-05 every move needs a reason")
  void aReasonIsRequired(String reason) {
    assertThatThrownBy(
            () ->
                RecoveryStateMachine.requireAllowed(
                    RecoveryState.OPEN, RecoveryState.ASSESSING, reason))
        .isInstanceOfSatisfying(
            ApiException.class,
            e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));
  }
}
