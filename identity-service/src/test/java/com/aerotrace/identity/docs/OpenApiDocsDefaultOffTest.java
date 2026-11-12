package com.aerotrace.identity.docs;

import static org.assertj.core.api.Assertions.assertThat;

import com.aerotrace.identity.base.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;

/** Nothing is configured here: the safe default must already keep the API map private. */
class OpenApiDocsDefaultOffTest extends AbstractIntegrationTest {

  @Test
  void theDefinitionIsNotServedUnlessSomeoneSwitchesItOn() {
    assertThat(get("/v3/api-docs").statusCode()).isEqualTo(401);
  }
}
