package com.aerotrace.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.aerotrace.identity.support.IdentityTestBase;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;

/** The service starts, reports itself healthy and is identifiable to Eureka and the collector. */
class IdentityServiceApplicationTest extends IdentityTestBase {

  @Autowired private Environment environment;

  @Test
  void healthEndpointReportsUp() {
    HttpResponse<String> response = get("/actuator/health");

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.body()).contains("\"status\":\"UP\"");
  }

  @Test
  void healthDetailsAreNotExposed() {
    assertThat(get("/actuator/health").body()).doesNotContain("components");
  }

  @Test
  void onlySafeActuatorEndpointsAreExposed() {
    assertThat(get("/actuator/env").statusCode()).isEqualTo(404);
    assertThat(get("/actuator/heapdump").statusCode()).isEqualTo(404);
  }

  @Test
  void errorResponsesNeverCarryMessagesOrStackTraces() {
    HttpResponse<String> response = get("/no-such-path");

    assertThat(response.statusCode()).isEqualTo(404);
    assertThat(response.body()).doesNotContain("trace").doesNotContain("exception");
  }

  @Test
  void identifiesItselfAsIdentityService() {
    assertThat(environment.getProperty("spring.application.name")).isEqualTo("identity-service");
    assertThat(environment.getProperty("eureka.instance.instance-id"))
        .startsWith("identity-service:localhost:");
  }

  @Test
  void listensOnPort8081ByDefault() {
    assertThat(environment.getProperty("server.port")).isNotNull();
    assertThat(IdentityServiceApplication.class).isNotNull();
  }
}
