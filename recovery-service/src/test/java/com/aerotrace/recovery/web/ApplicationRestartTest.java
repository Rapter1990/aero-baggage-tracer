package com.aerotrace.recovery.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.aerotrace.common.model.RecoveryState;
import com.aerotrace.recovery.RecoveryServiceApplication;
import com.aerotrace.recovery.base.AbstractRecoveryTest;
import com.aerotrace.recovery.model.dto.request.AssignRequest;
import com.aerotrace.recovery.model.dto.request.CreateCaseRequest;
import com.aerotrace.recovery.model.dto.request.TransitionRequest;
import com.aerotrace.recovery.model.dto.response.CaseResponse;
import com.aerotrace.recovery.model.entity.RecoveryCaseEntity;
import com.aerotrace.recovery.repository.OutboxEventRepository;
import com.aerotrace.recovery.repository.RecoveryCaseRepository;
import com.aerotrace.recovery.repository.StateTransitionRepository;
import com.aerotrace.recovery.service.RecoveryCaseService;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;

/**
 * AT-22 at the application level: the application is stopped (as when a pod is killed) and a new
 * one is started against the same PostgreSQL. Everything committed is still there, a retried move
 * is still answered with the original, and the case carries on from where it was. The same check
 * with a real Kubernetes pod restart on Minikube belongs to issue #18.
 */
@SpringBootTest
class ApplicationRestartTest extends AbstractRecoveryTest {

  @Autowired Environment environment;

  /**
   * A new application instance on the same database, Redis and capacity-service as this test. The
   * settings are command-line arguments, which outrank the YAML files (properties given to the
   * builder would be overridden by them).
   */
  private ConfigurableApplicationContext start() {
    return new SpringApplicationBuilder(RecoveryServiceApplication.class)
        .profiles("test")
        .run(
            "--server.port=0",
            "--spring.jmx.enabled=false",
            "--spring.datasource.url=" + environment.getProperty("spring.datasource.url"),
            "--spring.datasource.username=" + environment.getProperty("spring.datasource.username"),
            "--spring.datasource.password=" + environment.getProperty("spring.datasource.password"),
            "--spring.data.redis.host=" + environment.getProperty("spring.data.redis.host"),
            "--spring.data.redis.port=" + environment.getProperty("spring.data.redis.port"),
            "--spring.data.redis.password=" + environment.getProperty("spring.data.redis.password"),
            "--aerotrace.recovery.capacity.base-url="
                + environment.getProperty("aerotrace.recovery.capacity.base-url"));
  }

  @Test
  @DisplayName("AT-22 a restarted application keeps the committed case, its history and its events")
  void committedStateSurvivesARestart() {
    String bagTag = String.format("%010d", ThreadLocalRandom.current().nextLong(10_000_000_000L));
    String moveKey = "restart-move-" + UUID.randomUUID();
    CaseResponse reserved;
    UUID caseId;

    // The first instance: open, assess, hand to a handler, reserve capacity.
    try (ConfigurableApplicationContext first = start()) {
      RecoveryCaseService service = first.getBean(RecoveryCaseService.class);
      CaseResponse opened =
          service.create(new CreateCaseRequest(bagTag, "LEG-2", "HIGH_CONNECTION_RISK"), null);
      caseId = opened.caseId();
      service.transition(caseId, new TransitionRequest(RecoveryState.ASSESSING, 1L, "Picked up"));
      service.assign(caseId, new AssignRequest("agent-42", 2L));
      reserved =
          service.transition(
              caseId, new TransitionRequest(RecoveryState.RESERVED, 3L, "Slot secured"), moveKey);
      assertThat(reserved.version()).isEqualTo(4);
      assertThat(CAPACITY.holds).hasSize(1);
    }
    // The first instance is gone. A second one starts.
    try (ConfigurableApplicationContext second = start()) {
      RecoveryCaseRepository cases = second.getBean(RecoveryCaseRepository.class);
      StateTransitionRepository history = second.getBean(StateTransitionRepository.class);
      OutboxEventRepository outbox = second.getBean(OutboxEventRepository.class);
      RecoveryCaseService service = second.getBean(RecoveryCaseService.class);

      // Nothing committed was lost.
      RecoveryCaseEntity stored = cases.findById(caseId).orElseThrow();
      assertThat(stored.getState()).isEqualTo(RecoveryState.RESERVED);
      assertThat(stored.getVersion()).isEqualTo(4);
      assertThat(stored.getAssignedTo()).isEqualTo("agent-42");
      assertThat(stored.getReservationId()).isEqualTo(CAPACITY.holds.keySet().iterator().next());
      assertThat(history.findByCaseIdOrderByVersionAfterAsc(caseId)).hasSize(4);
      // The events of those changes are stored and still wait for the relay (no Kafka here).
      assertThat(outbox.findByMessageKeyOrderByCreatedAt(caseId.toString())).hasSize(4);

      // A retry that was in flight when the first instance died gets the original answer, and
      // capacity is not held a second time.
      CaseResponse retry =
          service.transition(
              caseId, new TransitionRequest(RecoveryState.RESERVED, 3L, "Slot secured"), moveKey);
      assertThat(retry.state()).isEqualTo(RecoveryState.RESERVED);
      assertThat(retry.version()).isEqualTo(reserved.version());
      assertThat(CAPACITY.holds).hasSize(1);
      assertThat(CAPACITY.calls.stream().filter(c -> c.method().equals("POST"))).hasSize(1);

      // And the case carries on.
      CaseResponse moving =
          service.transition(caseId, new TransitionRequest(RecoveryState.IN_TRANSIT, 4L, "Loaded"));
      assertThat(moving.state()).isEqualTo(RecoveryState.IN_TRANSIT);
      assertThat(moving.version()).isEqualTo(5);
    }
  }
}
