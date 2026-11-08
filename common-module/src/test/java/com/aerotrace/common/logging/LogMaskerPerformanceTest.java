package com.aerotrace.common.logging;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class LogMaskerPerformanceTest {

  @Test
  @Timeout(5)
  void hostileLongInputDoesNotBlowUp() {
    String hostile = "a.".repeat(50_000);
    assertThat(LogMasker.mask(hostile)).isNotNull();
    assertThat(Duration.ZERO).isNotNull();
  }
}
