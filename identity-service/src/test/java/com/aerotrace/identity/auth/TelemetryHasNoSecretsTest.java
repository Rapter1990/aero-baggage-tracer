package com.aerotrace.identity.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.aerotrace.identity.base.AbstractIntegrationTest;
import com.aerotrace.identity.support.StubCollector;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;

/**
 * Backlog #8: traces and metrics reach the collector and none of them (nor the logs sent with them)
 * contains a token or credential. Everything the service exports while handling registration,
 * sign-in, refresh, password reset and sign-out is inspected.
 */
class TelemetryHasNoSecretsTest extends AbstractIntegrationTest {

  private static final StubCollector TRACES = new StubCollector("/v1/traces");
  private static final StubCollector METRICS = new StubCollector("/v1/metrics");
  private static final StubCollector LOGS = new StubCollector("/v1/logs");

  @Autowired private JdbcClient jdbc;

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
    String name = registerPassenger();
    JsonNode tokens = login(name, PASSWORD);
    String access = tokens.get("accessToken").asString();
    String refresh = tokens.get("refreshToken").asString();
    postJson("/api/v1/auth/login", body("usernameOrEmail", name, "password", wrong));
    postJson("/api/v1/auth/login", body("usernameOrEmail", "ghost-user", "password", wrong));
    String rotated =
        json(postJson("/api/v1/auth/refresh-token", body("refreshToken", refresh)))
            .get("data")
            .get("refreshToken")
            .asString();
    HttpResponse<String> forgot =
        postJson("/api/v1/auth/forgot-password", body("usernameOrEmail", name));
    String resetToken = json(forgot).get("data").get("resetToken").asString();
    String newPassword = "telemetry-new-password-9";
    postJson(
        "/api/v1/auth/reset-password", body("resetToken", resetToken, "newPassword", newPassword));
    postJson("/api/v1/auth/logout", body("refreshToken", rotated));
    // A call that carries a token as a bearer credential must not leak it either.
    send("POST", "/api/v1/auth/register", "{}", access);
    String hash =
        jdbc.sql("select password_hash from identity.app_user where username = :u")
            .param("u", name)
            .query(String.class)
            .single();
    LoggerFactory.getLogger("telemetry.test").info("marker after the sign-ins");

    // The requests really were traced and counted, and the log line really was shipped.
    TRACES.awaitPushContaining("auth/login");
    METRICS.awaitPushContaining("identity.auth.token.requests");
    LOGS.awaitPushContaining("marker after the sign-ins");

    for (StubCollector collector : new StubCollector[] {TRACES, METRICS, LOGS}) {
      for (StubCollector.Push push : collector.pushes()) {
        assertThat(push.body())
            .doesNotContain(PASSWORD)
            .doesNotContain(wrong)
            .doesNotContain(newPassword)
            .doesNotContain(access)
            .doesNotContain(access.substring(access.lastIndexOf('.') + 1))
            .doesNotContain(refresh)
            .doesNotContain(rotated)
            .doesNotContain(resetToken)
            .doesNotContain(hash)
            .doesNotContain("Bearer ");
      }
    }
  }
}
