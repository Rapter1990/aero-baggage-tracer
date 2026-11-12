package com.aerotrace.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.aerotrace.gateway.support.GatewayTestBase;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** The Swagger UI dropdown lists every service; each definition is proxied through the gateway. */
class OpenApiDocsTest extends GatewayTestBase {

  private static final List<String> SERVICES =
      List.of(
          "identity-service",
          "journey-service",
          "recovery-service",
          "capacity-service",
          "search-service");

  @Test
  void swaggerUiIsServedWithoutAToken() {
    HttpResponse<String> page = get("/swagger-ui/index.html", null);

    assertThat(page.statusCode()).isEqualTo(200);
    assertThat(page.body()).containsIgnoringCase("swagger");
  }

  @Test
  void dropdownListsEveryServiceWithAGatewayRelativeUrl() {
    HttpResponse<String> config = get("/v3/api-docs/swagger-config", null);

    assertThat(config.statusCode()).isEqualTo(200);
    List<String> names = new ArrayList<>();
    for (JsonNode entry : json(config).get("urls")) {
      String name = entry.get("name").asString();
      names.add(name);
      assertThat(entry.get("url").asString()).isEqualTo("/service-docs/" + name);
    }
    assertThat(names).containsExactlyInAnyOrderElementsOf(SERVICES);
  }

  @Test
  void serviceDefinitionsAreRoutedThroughEurekaAndNeedNoToken() {
    for (String service : SERVICES) {
      // No service instances exist in this test, so the proxy fails after security allowed it.
      int status = get("/service-docs/" + service, null).statusCode();
      assertThat(status).as(service).isNotIn(401, 403, 404);
    }
  }

  @Test
  void apiRoutesStillNeedAToken() {
    assertThat(get("/api/v1/bags/AB123456/journey", null).statusCode()).isEqualTo(401);
  }
}
