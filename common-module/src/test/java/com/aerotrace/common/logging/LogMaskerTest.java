package com.aerotrace.common.logging;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class LogMaskerTest {

  private static final String JWT =
      "eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiJ1c2VyLTEifQ.c2lnbmF0dXJlX3ZhbHVl";

  @Test
  void masksJwtAndBearerHeader() {
    String masked = LogMasker.mask("Authorization: Bearer " + JWT + " token=" + JWT);
    assertThat(masked).doesNotContain(JWT).doesNotContain("eyJ");
    assertThat(masked).contains("[REDACTED]");
  }

  @Test
  void masksEmailsAndPassengerRefs() {
    String masked = LogMasker.mask("user jane.doe@example.com passengerRef=P-901 failed");
    assertThat(masked).doesNotContain("jane.doe@example.com").doesNotContain("P-901");
    assertThat(masked).contains("failed");
  }

  @Test
  void leavesHarmlessTextUntouched() {
    assertThat(LogMasker.mask("bagTag AB123456 scanned at FRA"))
        .isEqualTo("bagTag AB123456 scanned at FRA");
  }

  @Test
  void handlesNullAndEmpty() {
    assertThat(LogMasker.mask(null)).isNull();
    assertThat(LogMasker.mask("")).isEmpty();
    assertThat(LogMasker.maskPassengerRef(null)).isEqualTo("[REDACTED]");
  }

  @Test
  void maskPassengerRefKeepsNoIdentifier() {
    assertThat(LogMasker.maskPassengerRef("P-901")).isEqualTo("[REDACTED]");
  }
}
