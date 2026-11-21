package com.aerotrace.notification.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aerotrace.common.model.RecoveryState;
import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TemplateSelectorTest {

  private static final Map<RecoveryState, String> EXPECTED =
      Map.of(
          RecoveryState.OPEN, "CASE_OPENED",
          RecoveryState.ASSESSING, "CASE_ASSESSING",
          RecoveryState.RESERVED, "CASE_RESERVED",
          RecoveryState.IN_TRANSIT, "CASE_IN_TRANSIT",
          RecoveryState.RESOLVED, "CASE_RESOLVED",
          RecoveryState.CLOSED_AS_UNRECOVERABLE, "CASE_CLOSED_AS_UNRECOVERABLE",
          RecoveryState.CANCELLED, "CASE_CANCELLED");

  private final TemplateSelector selector = new TemplateSelector();

  @Test
  @DisplayName("BR-08 every case state has its own template, named CASE_ plus the state")
  void everyStateHasATemplate() {
    assertThat(EXPECTED).hasSize(RecoveryState.values().length);
    EXPECTED.forEach(
        (state, template) ->
            assertThat(selector.select(state)).as("template of %s", state).isEqualTo(template));
  }

  @Test
  @DisplayName("BR-08 different states never share a template, so they never dedupe each other")
  void templatesAreDistinct() {
    int distinct =
        Arrays.stream(RecoveryState.values())
            .map(selector::select)
            .collect(Collectors.toSet())
            .size();

    assertThat(distinct).isEqualTo(RecoveryState.values().length);
  }

  @Test
  @DisplayName("BR-08 a missing state (an enum value added later reads as null) is refused")
  void unknownStateIsRefused() {
    assertThatThrownBy(() -> selector.select(null)).isInstanceOf(IllegalArgumentException.class);
  }
}
