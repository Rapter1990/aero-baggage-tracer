package com.aerotrace.journey.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.utility.DockerImageName;

/**
 * Tests the real {@code observability/prometheus-rules.yml}: the alert on the gauge of {@code
 * ContractStepMonitor}. {@code promtool} (in the Prometheus image that Compose runs) feeds it
 * made-up series and checks when the alert fires. The cases live here because Prometheus refuses
 * test sections in a rules file.
 */
class ContractStepAlertRuleTest {

  private static final Path RULES = Path.of("..", "observability", "prometheus-rules.yml");

  private static final String CASES =
      """
      rule_files:
        - prometheus-rules.yml

      evaluation_interval: 1m

      tests:
        # Pending for a day: silent until 24 hours have passed, then firing once for the service
        # even with two replicas.
        - interval: 1m
          input_series:
            - series: 'journey_migration_contract_pending{service_name="journey-service", instance="a"}'
              values: "1x1500"
            - series: 'journey_migration_contract_pending{service_name="journey-service", instance="b"}'
              values: "1x1500"
          alert_rule_test:
            - eval_time: 23h59m
              alertname: JourneyContractStepPending
              exp_alerts: []
            - eval_time: 24h1m
              alertname: JourneyContractStepPending
              exp_alerts:
                - exp_labels:
                    severity: warning
                    service_name: journey-service
                  exp_annotations:
                    summary: "The scan_source contract step (changeset 006) has not been applied for 24 hours"
                    description: >-
                      journey-service still runs without the "contract" Liquibase context, so
                      scan_audit.scan_source is nullable although the application treats it as mandatory.
                      Once every replica writes scan_source, set
                      JOURNEY_LIQUIBASE_CONTEXTS=expand,contract and restart (docs/migrations/README.md).

        # A staged rollout that applies the contract step within the day never alerts.
        - interval: 1m
          input_series:
            - series: 'journey_migration_contract_pending{service_name="journey-service"}'
              values: "1x600 0x900"
          alert_rule_test:
            - eval_time: 24h1m
              alertname: JourneyContractStepPending
              exp_alerts: []

        # The default (expand,contract): the gauge is 0 from the start.
        - interval: 1m
          input_series:
            - series: 'journey_migration_contract_pending{service_name="journey-service"}'
              values: "0x1500"
          alert_rule_test:
            - eval_time: 24h1m
              alertname: JourneyContractStepPending
              exp_alerts: []

        # An unreadable changelog table (NaN) is not reported as a pending step.
        - interval: 1m
          input_series:
            - series: 'journey_migration_contract_pending{service_name="journey-service"}'
              values: "NaN NaN NaN NaN"
          alert_rule_test:
            - eval_time: 3m
              alertname: JourneyContractStepPending
              exp_alerts: []
      """;

  @Test
  @DisplayName("AT-17 the contract-step alert fires after 24 hours at 1, and only then")
  void contractStepAlertRule() throws Exception {
    boolean docker = DockerClientFactory.instance().isDockerAvailable();
    if (System.getenv("CI") != null) {
      assertThat(docker).as("Docker is required to run the journey-service tests in CI").isTrue();
    } else {
      assumeTrue(docker, "Docker is not available");
    }
    byte[] rules = Files.readAllBytes(RULES);

    try (GenericContainer<?> prometheus =
        new GenericContainer<>(DockerImageName.parse("prom/prometheus:latest"))
            .withCreateContainerCmdModifier(cmd -> cmd.withEntrypoint("sleep"))
            .withCommand("300")
            .withCopyToContainer(Transferable.of(rules, 0644), "/rules/prometheus-rules.yml")
            .withCopyToContainer(Transferable.of(CASES, 0644), "/rules/cases.yml")) {
      prometheus.start();

      ExecResult result =
          prometheus.execInContainer("promtool", "test", "rules", "/rules/cases.yml");

      assertThat(result.getExitCode())
          .as("promtool output:%n%s%s", result.getStdout(), result.getStderr())
          .isZero();
    }
  }
}
