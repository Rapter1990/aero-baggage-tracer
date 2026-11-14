package com.aerotrace.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.aerotrace.identity.base.AbstractIntegrationTest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;

/** The service starts, reports itself healthy and is identifiable to Eureka and the collector. */
class IdentityServiceApplicationTest extends AbstractIntegrationTest {

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
    // Anything not explicitly allowed is refused before it can even be looked up.
    assertThat(get("/actuator/env").statusCode()).isEqualTo(401);
    assertThat(get("/actuator/heapdump").statusCode()).isEqualTo(401);
  }

  @Test
  void errorResponsesNeverCarryMessagesOrStackTraces() {
    HttpResponse<String> response = get("/no-such-path");

    assertThat(response.statusCode()).isEqualTo(401);
    // The shared RFC 7807 contract, with nothing that reveals internals.
    assertThat(response.body())
        .contains("\"code\":\"UNAUTHENTICATED\"")
        .doesNotContain("stackTrace")
        .doesNotContain("Exception")
        .doesNotContain("\tat ");
  }

  @Test
  void identifiesItselfAsIdentityService() {
    assertThat(environment.getProperty("spring.application.name")).isEqualTo("identity-service");
    assertThat(environment.getProperty("eureka.instance.instance-id"))
        .startsWith("identity-service:localhost:");
  }

  @Test
  void listensOnPort1112ByDefault() {
    // The test runs on a random port, so read the configured default from the file itself.
    var yaml = new org.springframework.beans.factory.config.YamlPropertiesFactoryBean();
    yaml.setResources(new org.springframework.core.io.ClassPathResource("application.yml"));

    assertThat(yaml.getObject()).containsEntry("server.port", 1112);
    assertThat(IdentityServiceApplication.class).isNotNull();
  }
}
