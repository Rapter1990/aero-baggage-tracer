package com.aerotrace.identity.docs;

import static org.assertj.core.api.Assertions.assertThat;

import com.aerotrace.identity.base.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;

/** Nothing is configured here: the definition is served by default. */
class OpenApiDocsDefaultOnTest extends AbstractIntegrationTest {

  @Test
  void theDefinitionIsServedUnlessSomeoneSwitchesItOff() {
    assertThat(get("/v3/api-docs").statusCode()).isEqualTo(200);
  }
}
