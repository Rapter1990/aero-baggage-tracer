package com.aerotrace.capacity.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aerotrace.capacity.base.AbstractCapacityTest;
import com.aerotrace.capacity.repository.CapacityResourceRepository;
import com.aerotrace.common.security.Role;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * AT-19: with Redis offline there is no incorrect reservation. PostgreSQL alone decides capacity:
 * the reservation path has no Redis client at all (Redis is not even a dependency of this service),
 * so an unreachable Redis (host and port below point at nothing) cannot change a single answer. The
 * same 50-for-10 race as AT-11 must end with exactly 10 holds and nothing over-booked.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {"spring.data.redis.host=127.0.0.1", "spring.data.redis.port=1"})
class RedisOfflineTest extends AbstractCapacityTest {

  @Autowired MockMvc mockMvc;
  @Autowired CapacityResourceRepository resources;
  @MockitoBean KafkaTemplate<String, String> kafka;

  @Test
  @DisplayName("AT-19 Redis offline: 50 concurrent requests for 10 slots still book exactly 10")
  void noOverbookingWithRedisOffline() throws Exception {
    String leg = newLeg();
    mockMvc
        .perform(
            put("/api/v1/capacity/resources/{id}", leg)
                .with(as(Role.ADMIN))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"totalSlots\":10}"))
        .andExpect(status().isOk());

    ExecutorService pool = Executors.newFixedThreadPool(50);
    CountDownLatch go = new CountDownLatch(1);
    int created = 0;
    try {
      List<Future<Integer>> results = new ArrayList<>();
      for (int i = 0; i < 50; i++) {
        String body =
            "{\"reservationId\":\""
                + UUID.randomUUID()
                + "\",\"caseId\":\""
                + UUID.randomUUID()
                + "\",\"bagTag\":\"0123456789\",\"legId\":\""
                + leg
                + "\",\"slots\":1}";
        results.add(
            pool.submit(
                () -> {
                  go.await();
                  return mockMvc
                      .perform(
                          post("/api/v1/capacity/reservations")
                              .with(as(Role.DISPATCHER))
                              .contentType(MediaType.APPLICATION_JSON)
                              .content(body))
                      .andReturn()
                      .getResponse()
                      .getStatus();
                }));
      }
      go.countDown();
      for (Future<Integer> result : results) {
        if (result.get(60, TimeUnit.SECONDS) == 201) {
          created++;
        }
      }
    } finally {
      pool.shutdownNow();
    }

    assertThat(created).isEqualTo(10);
    assertThat(resources.findById(leg).orElseThrow().getRemainingSlots()).isZero();
  }
}
