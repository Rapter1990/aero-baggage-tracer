package com.aerotrace.identity.token;

import static org.assertj.core.api.Assertions.assertThat;

import com.aerotrace.identity.support.IdentityTestBase;
import com.aerotrace.identity.support.StubCollector;
import com.aerotrace.identity.user.UserService;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.json.JsonMapper;

/**
 * Backlog #8: traces and metrics reach the collector and none of them (nor the logs sent with them)
 * contains a token or credential. Everything the service exports while handling a successful and a
 * failed sign-in is inspected.
 */
@TestPropertySource(
    properties = {
      "aerotrace.identity.seed.enabled=true",
      "aerotrace.identity.seed.password=seed-test-password-1"
    })
class TelemetryHasNoSecretsTest extends IdentityTestBase {

  private static final StubCollector TRACES = new StubCollector("/v1/traces");
  private static final StubCollector METRICS = new StubCollector("/v1/metrics");
  private static final StubCollector LOGS = new StubCollector("/v1/logs");

  @Autowired private UserService users;

  @DynamicPropertySource
  static void telemetry(DynamicPropertyRegistry registry) {
    registry.add("management.tracing.export.otlp.enabled", () -> "true");
    registry.add(
        "management.opentelemetry.tracing.export.otlp.endpoint", () -> TRACES.url("/v1/traces"));
    registry.add("management.otlp.metrics.export.enabled", () -> "true");
    registry.add("management.otlp.metrics.export.step", () -> "1s");
    registry.add("management.otlp.metrics.export.url", () -> METRICS.url("/v1/metrics"));
    registry.add("management.logging.export.otlp.enabled", () -> "true");
    registry.add(
        "management.opentelemetry.logging.export.otlp.endpoint", () -> LOGS.url("/v1/logs"));
  }

  @Test
  void exportedTracesMetricsAndLogsHoldNoTokenOrCredential() throws InterruptedException {
    String wrong = "wrong-password-telemetry-77";
    HttpResponse<String> ok =
        postJson(
            "/api/v1/auth/token", "{\"username\":\"agent\",\"password\":\"seed-test-password-1\"}");
    postJson("/api/v1/auth/token", "{\"username\":\"agent\",\"password\":\"" + wrong + "\"}");
    postJson("/api/v1/auth/token", "{\"username\":\"ghost-user\",\"password\":\"" + wrong + "\"}");
    String token = JsonMapper.builder().build().readTree(ok.body()).get("access_token").asString();
    // A call that carries the token as a bearer credential must not leak it either.
    send("GET", "/api/v1/auth/me", null, token);
    send("GET", "/api/v1/auth/me", null, "forged." + token.substring(token.indexOf('.') + 1));
    String hash = users.findByUsername("agent").orElseThrow().passwordHash();
    LoggerFactory.getLogger("telemetry.test").info("marker after the sign-ins");

    // The sign-in requests really were traced and counted, and the log line really was shipped.
    TRACES.awaitPushContaining("auth/token");
    METRICS.awaitPushContaining("identity.auth.token.requests");
    LOGS.awaitPushContaining("marker after the sign-ins");

    for (StubCollector collector : new StubCollector[] {TRACES, METRICS, LOGS}) {
      for (StubCollector.Push push : collector.pushes()) {
        assertThat(push.body())
            .doesNotContain("seed-test-password-1")
            .doesNotContain(wrong)
            .doesNotContain(token)
            .doesNotContain(token.substring(token.lastIndexOf('.') + 1))
            .doesNotContain(hash)
            .doesNotContain("Bearer ");
      }
    }
  }
}
