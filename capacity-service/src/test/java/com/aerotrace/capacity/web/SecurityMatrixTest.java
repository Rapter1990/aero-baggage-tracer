package com.aerotrace.capacity.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aerotrace.capacity.base.AbstractCapacityTest;
import com.aerotrace.common.security.Role;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** Role by endpoint (spec section 11), including the service token. */
@SpringBootTest
@AutoConfigureMockMvc
class SecurityMatrixTest extends AbstractCapacityTest {

  private static final String RESERVATIONS = "/api/v1/capacity/reservations";
  private static final String RESOURCES = "/api/v1/capacity/resources";
  private static final String HOLD =
      "{\"reservationId\":\"%s\",\"caseId\":\"%s\",\"bagTag\":\"0123456789\","
          + "\"legId\":\"NO-SUCH-LEG\",\"slots\":1}";

  @Autowired MockMvc mockMvc;
  @MockitoBean KafkaTemplate<String, String> kafka;

  private static String hold() {
    return HOLD.formatted(UUID.randomUUID(), UUID.randomUUID());
  }

  @Test
  @DisplayName("AT-16 no token answers 401 and a passenger 403 on every capacity endpoint")
  void noTokenAndPassenger() throws Exception {
    mockMvc
        .perform(post(RESERVATIONS).contentType(MediaType.APPLICATION_JSON).content(hold()))
        .andExpect(status().isUnauthorized());
    mockMvc
        .perform(delete(RESERVATIONS + "/{id}", UUID.randomUUID()))
        .andExpect(status().isUnauthorized());
    mockMvc.perform(get(RESOURCES)).andExpect(status().isUnauthorized());

    mockMvc
        .perform(
            post(RESERVATIONS)
                .with(as(Role.PASSENGER))
                .contentType(MediaType.APPLICATION_JSON)
                .content(hold()))
        .andExpect(status().isForbidden());
    mockMvc
        .perform(delete(RESERVATIONS + "/{id}", UUID.randomUUID()).with(as(Role.PASSENGER)))
        .andExpect(status().isForbidden());
    mockMvc.perform(get(RESOURCES).with(as(Role.PASSENGER))).andExpect(status().isForbidden());
  }

  @Test
  @DisplayName("staff hold slots; resources are managed by admins only")
  void resourceManagementIsAdminOnly() throws Exception {
    for (Role staff : new Role[] {Role.AGENT, Role.DISPATCHER}) {
      mockMvc
          .perform(
              put(RESOURCES + "/{id}", "LEG-SEC")
                  .with(as(staff))
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{\"totalSlots\":5}"))
          .andExpect(status().isForbidden());
      mockMvc.perform(get(RESOURCES).with(as(staff))).andExpect(status().isOk());
      // Allowed to hold: an unknown leg is a business answer (409), not a permission one.
      mockMvc
          .perform(
              post(RESERVATIONS)
                  .with(as(staff))
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(hold()))
          .andExpect(status().isConflict());
    }
    mockMvc
        .perform(
            put(RESOURCES + "/{id}", "LEG-SEC")
                .with(as(Role.ADMIN))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"totalSlots\":5}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.totalSlots").value(5));
  }

  @Test
  @DisplayName("a service with the service token may release and confirm, and only that")
  void serviceTokenIsLimitedToReleaseAndConfirm() throws Exception {
    UUID id = UUID.randomUUID();
    // Passes security: the answer is 404 because there is no such hold.
    mockMvc
        .perform(delete(RESERVATIONS + "/{id}", id).header("X-Service-Token", "test-service-token"))
        .andExpect(status().isNotFound());
    mockMvc
        .perform(
            put(RESERVATIONS + "/{id}/confirmation", id)
                .header("X-Service-Token", "test-service-token"))
        .andExpect(status().isNotFound());
    // It cannot hold slots or manage resources.
    mockMvc
        .perform(
            post(RESERVATIONS)
                .header("X-Service-Token", "test-service-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(hold()))
        .andExpect(status().isForbidden());
    mockMvc
        .perform(
            put(RESOURCES + "/{id}", "LEG-SEC")
                .header("X-Service-Token", "test-service-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"totalSlots\":5}"))
        .andExpect(status().isForbidden());
  }

  @Test
  @DisplayName("a wrong service token changes nothing: the call stays unauthenticated")
  void wrongServiceTokenIsIgnored() throws Exception {
    mockMvc
        .perform(
            delete(RESERVATIONS + "/{id}", UUID.randomUUID()).header("X-Service-Token", "nope"))
        .andExpect(status().isUnauthorized());
    mockMvc
        .perform(delete(RESERVATIONS + "/{id}", UUID.randomUUID()).header("X-Service-Token", ""))
        .andExpect(status().isUnauthorized());
  }

  @Test
  @DisplayName("anything not listed is denied")
  void unlistedPathsAreDenied() throws Exception {
    mockMvc
        .perform(get("/api/v1/capacity/other").with(as(Role.ADMIN)))
        .andExpect(status().isForbidden());
  }
}
