package com.aerotrace.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.aerotrace.gateway.support.GatewayTestBase;
import com.aerotrace.gateway.support.TestJwks;
import com.nimbusds.jose.jwk.RSAKey;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Tracing is active: the gateway starts or continues a trace and propagates it downstream. */
class TracingTest extends GatewayTestBase {

  private static RSAKey key;

  @BeforeAll
  static void publishKey() {
    key = TestJwks.publish("kid-trace");
  }

  private static String token() {
    return TestJwks.sign(
        key, TestJwks.claims(UUID.randomUUID().toString(), List.of("AGENT")).build());
  }

  @Test
  void startsATraceAndSendsItDownstream() {
    HttpResponse<String> response = get("/api/v1/echo/ping", token());

    assertThat(json(response).get("traceparent").asString())
        .matches("00-[0-9a-f]{32}-[0-9a-f]{16}-0[01]");
  }

  @Test
  void continuesATraceStartedByTheCaller() {
    String traceId = "4bf92f3577b34da6a3ce929d0e0e4736";

    HttpResponse<String> response =
        get("/api/v1/echo/ping", token(), "traceparent", "00-" + traceId + "-00f067aa0ba902b7-01");

    assertThat(json(response).get("traceparent").asString()).startsWith("00-" + traceId + "-");
    // The gateway is a hop of its own: a new span, not the caller's span id passed through.
    assertThat(json(response).get("traceparent").asString()).doesNotContain("-00f067aa0ba902b7-");
  }
}
