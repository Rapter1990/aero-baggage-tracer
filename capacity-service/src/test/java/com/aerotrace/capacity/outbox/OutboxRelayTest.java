package com.aerotrace.capacity.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aerotrace.capacity.base.AbstractCapacityTest;
import com.aerotrace.capacity.repository.OutboxEventRepository;
import com.aerotrace.capacity.service.CapacityService;
import com.aerotrace.common.event.Topics;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** The relay publishes the capacity events at least once and keeps the ones the broker refuses. */
@SpringBootTest
class OutboxRelayTest extends AbstractCapacityTest {

  @Autowired CapacityService service;
  @Autowired OutboxRelay relay;
  @Autowired OutboxEventRepository outbox;
  @MockitoBean KafkaTemplate<String, String> kafka;

  @SuppressWarnings("unchecked")
  @Test
  @DisplayName("a capacity change is published to recovery.capacity.changed.v1 keyed by resource")
  void publishesCapacityChanges() {
    when(kafka.send(anyString(), anyString(), anyString()))
        .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));
    String leg = newLeg();
    service.setTotal(leg, 7);

    int sent;
    int total = 0;
    do {
      sent = relay.publishPending();
      total += sent;
    } while (sent > 0);

    assertThat(total).isGreaterThanOrEqualTo(1);
    verify(kafka, atLeastOnce()).send(eq(Topics.CAPACITY_CHANGED), eq(leg), anyString());
    assertThat(outbox.countPending()).isZero();
  }

  @Test
  @DisplayName("a broker failure leaves the event pending, so it goes out when the broker is back")
  void keepsEventsWhileTheBrokerIsDown() {
    when(kafka.send(anyString(), anyString(), anyString()))
        .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker down")));
    service.setTotal(newLeg(), 3);

    int sent = relay.publishPending();

    assertThat(sent).isZero();
    assertThat(outbox.countPending()).isGreaterThanOrEqualTo(1);
  }
}
