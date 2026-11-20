package com.aerotrace.common.correlation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

class CorrelationScopeTest {

  @AfterEach
  void clear() {
    MDC.clear();
  }

  @Test
  @DisplayName("an event's correlation id is in the logging context while it is handled")
  void restoresTheEventId() {
    try (CorrelationScope ignored = CorrelationScope.of("corr-42")) {
      assertThat(MDC.get(CorrelationId.MDC_KEY)).isEqualTo("corr-42");
    }
    assertThat(MDC.get(CorrelationId.MDC_KEY)).isNull();
  }

  @Test
  @DisplayName("the previous value comes back afterwards")
  void putsThePreviousValueBack() {
    MDC.put(CorrelationId.MDC_KEY, "outer");
    try (CorrelationScope ignored = CorrelationScope.of("inner")) {
      assertThat(MDC.get(CorrelationId.MDC_KEY)).isEqualTo("inner");
    }
    assertThat(MDC.get(CorrelationId.MDC_KEY)).isEqualTo("outer");
  }

  @Test
  @DisplayName("a missing or unsafe id is never generated or copied: the context has none")
  void neverGeneratesOrCopiesAnUnsafeId() {
    MDC.put(CorrelationId.MDC_KEY, "outer");
    try (CorrelationScope ignored = CorrelationScope.of(null)) {
      assertThat(MDC.get(CorrelationId.MDC_KEY)).isNull();
    }
    try (CorrelationScope ignored = CorrelationScope.of("bad value\nwith a newline")) {
      assertThat(MDC.get(CorrelationId.MDC_KEY)).isNull();
    }
    assertThat(MDC.get(CorrelationId.MDC_KEY)).isEqualTo("outer");
  }
}
