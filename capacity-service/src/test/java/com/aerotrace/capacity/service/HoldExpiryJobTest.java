package com.aerotrace.capacity.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.aerotrace.capacity.base.AbstractCapacityTest;
import com.aerotrace.capacity.model.dto.ReservationRequest;
import com.aerotrace.capacity.repository.CapacityResourceRepository;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** The scheduled job is on only when {@code expiry-enabled} is set; here it is. */
@SpringBootTest
@TestPropertySource(properties = "aerotrace.capacity.hold.expiry-enabled=true")
class HoldExpiryJobTest extends AbstractCapacityTest {

  @Autowired CapacityService service;
  @Autowired HoldExpiryJob job;
  @Autowired CapacityResourceRepository resources;
  @MockitoBean KafkaTemplate<String, String> kafka;

  @Test
  @DisplayName("an unconfirmed hold is released by the expiry job, a confirmed one is kept")
  void sweepReleasesUnconfirmedHolds() {
    String leg = newLeg();
    service.setTotal(leg, 4);
    UUID unconfirmed = UUID.randomUUID();
    UUID confirmed = UUID.randomUUID();
    service.reserve(request(unconfirmed, leg, 2), null);
    service.reserve(request(confirmed, leg, 1), null);
    service.confirm(confirmed);
    assertThat(resources.findById(leg).orElseThrow().getRemainingSlots()).isEqualTo(1);

    job.sweep();

    assertThat(resources.findById(leg).orElseThrow().getRemainingSlots()).isEqualTo(3);
    // The expired hold is gone for good: its id answers 409 and does not hold again.
    org.junit.jupiter.api.Assertions.assertThrows(
        com.aerotrace.common.error.ApiException.class,
        () -> service.reserve(request(unconfirmed, leg, 2), null));
  }

  private static ReservationRequest request(UUID id, String leg, int slots) {
    return new ReservationRequest(id, UUID.randomUUID(), "0123456789", leg, slots);
  }
}
