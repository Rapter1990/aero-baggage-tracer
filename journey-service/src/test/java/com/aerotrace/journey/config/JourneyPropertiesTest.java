package com.aerotrace.journey.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class JourneyPropertiesTest {

  @Test
  void emptyKeySettingsFallBackToTheBundledDevelopmentKey() {
    assertThat(new JourneyProperties.Jwt("aud", "", "").publicKeyLocation())
        .isEqualTo(JourneyProperties.Jwt.DEFAULT_PUBLIC_KEY_LOCATION);
    assertThat(new JourneyProperties.Jwt("aud", null, null).publicKeyLocation())
        .isEqualTo(JourneyProperties.Jwt.DEFAULT_PUBLIC_KEY_LOCATION);
  }

  @Test
  void anyConfiguredKeyIsKept() {
    assertThat(new JourneyProperties.Jwt("aud", "", "file:/run/secrets/x.pem").publicKeyLocation())
        .isEqualTo("file:/run/secrets/x.pem");
    assertThat(new JourneyProperties.Jwt("aud", "PEM", "").publicKeyLocation()).isEmpty();
  }
}
