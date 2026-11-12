package com.aerotrace.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.aerotrace.gateway.support.GatewayTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;

/** The production timeouts for calls to downstream services, under Boot 4's property names. */
class HttpClientSettingsTest extends GatewayTestBase {

  @Autowired private Environment environment;

  @Test
  void downstreamTimeoutsAreConfigured() {
    assertThat(environment.getProperty("spring.http.clients.connect-timeout")).isEqualTo("2s");
    assertThat(environment.getProperty("spring.http.clients.read-timeout")).isEqualTo("30s");
  }

  @Test
  void theIneffectiveGatewaySpecificKeysAreNotUsed() {
    assertThat(
            environment.containsProperty(
                "spring.cloud.gateway.server.webmvc.http-client.connect-timeout"))
        .isFalse();
    assertThat(
            environment.containsProperty(
                "spring.cloud.gateway.server.webmvc.http-client.read-timeout"))
        .isFalse();
  }
}
