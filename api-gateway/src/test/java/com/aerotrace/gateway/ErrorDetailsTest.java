package com.aerotrace.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.aerotrace.gateway.support.GatewayTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;

/**
 * Error responses must not leak exception messages, stack traces or binding details. Spring Boot 4
 * moved these settings from {@code server.error.*} to {@code spring.web.error.*}; the old keys are
 * no longer honoured, so they must not be the ones in use.
 */
class ErrorDetailsTest extends GatewayTestBase {

  @Autowired private Environment environment;

  @Test
  void errorDetailsAreExplicitlyHiddenWithTheCurrentPropertyNames() {
    assertThat(environment.getProperty("spring.web.error.include-message")).isEqualTo("never");
    assertThat(environment.getProperty("spring.web.error.include-stacktrace")).isEqualTo("never");
    assertThat(environment.getProperty("spring.web.error.include-binding-errors"))
        .isEqualTo("never");
    assertThat(environment.getProperty("spring.web.error.include-exception")).isEqualTo("false");
  }

  @Test
  void theRemovedServerErrorPropertiesAreNotUsed() {
    for (String key :
        new String[] {"include-message", "include-stacktrace", "include-binding-errors"}) {
      assertThat(environment.containsProperty("server.error." + key)).as(key).isFalse();
    }
  }
}
