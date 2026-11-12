package com.aerotrace.identity.docs;

import static org.assertj.core.api.Assertions.assertThat;

import com.aerotrace.identity.base.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/** Outside development the definition is switched off, as on the gateway. */
@TestPropertySource(properties = "aerotrace.identity.docs.enabled=false")
class OpenApiDocsDisabledTest extends AbstractIntegrationTest {

  @Test
  void theDefinitionIsNotServed() {
    assertThat(get("/v3/api-docs").statusCode()).isEqualTo(401);
    assertThat(get("/v3/api-docs.yaml").statusCode()).isEqualTo(401);
  }

  @Test
  void theServiceItselfKeepsWorking() {
    assertThat(get("/.well-known/jwks.json").statusCode()).isEqualTo(200);
  }
}
