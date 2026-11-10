package com.aerotrace.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.aerotrace.gateway.support.GatewayTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/** In production the docs can be switched off; they then need a token like everything else. */
@TestPropertySource(properties = "aerotrace.gateway.docs.enabled=false")
class OpenApiDocsDisabledTest extends GatewayTestBase {

  @Test
  void documentationIsNotPublicWhenDisabled() {
    assertThat(get("/swagger-ui/index.html", null).statusCode()).isEqualTo(401);
    assertThat(get("/v3/api-docs/swagger-config", null).statusCode()).isEqualTo(401);
    assertThat(get("/service-docs/identity-service", null).statusCode()).isEqualTo(401);
  }
}
