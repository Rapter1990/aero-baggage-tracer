package com.aerotrace.search.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aerotrace.common.security.Role;
import com.aerotrace.search.base.AbstractSearchTest;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Role by endpoint (ADR 0004, decision 8): staff search and report, dispatchers and admins read the
 * index status, only admins rebuild, and a passenger is refused everywhere.
 */
@SpringBootTest
@AutoConfigureMockMvc
class SecurityMatrixTest extends AbstractSearchTest {

  private static final String REPORT =
      "/api/v1/reports/airport/IST?from=2026-01-01T00:00:00Z&to=2026-01-01T02:00:00Z";

  @Autowired MockMvc mockMvc;

  private static List<MockHttpServletRequestBuilder> everyEndpoint() {
    return List.of(
        get("/api/v1/search/bags"),
        get("/api/v1/search/index/status"),
        get(REPORT),
        post("/api/v1/admin/search/reindex").header("Idempotency-Key", UUID.randomUUID()),
        get("/api/v1/admin/search/reindex/{jobId}", UUID.randomUUID()));
  }

  @Test
  @DisplayName("AT-16 no token answers 401 with the ApiError shape on every search endpoint")
  void noTokenIs401() throws Exception {
    for (RequestBuilder request : everyEndpoint()) {
      mockMvc
          .perform(request)
          .andExpect(status().isUnauthorized())
          .andExpect(header().string("WWW-Authenticate", "Bearer"))
          .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }
  }

  @Test
  @DisplayName("AT-16 a passenger is refused with 403 on every search, report and admin endpoint")
  void passengerIsRefusedEverywhere() throws Exception {
    for (MockHttpServletRequestBuilder request : everyEndpoint()) {
      mockMvc
          .perform(request.with(as(Role.PASSENGER)))
          .andExpect(status().isForbidden())
          .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }
  }

  @Test
  @DisplayName("AT-16 the index status is for dispatchers and admins; an agent gets 403")
  void indexStatusIsNotForAgents() throws Exception {
    mockMvc
        .perform(get("/api/v1/search/index/status").with(as(Role.AGENT)))
        .andExpect(status().isForbidden());
  }

  @Test
  @DisplayName("AT-16 only an admin may start or read a rebuild")
  void reindexIsAdminOnly() throws Exception {
    for (Role staff : new Role[] {Role.AGENT, Role.DISPATCHER}) {
      mockMvc
          .perform(
              post("/api/v1/admin/search/reindex")
                  .header("Idempotency-Key", UUID.randomUUID())
                  .with(as(staff)))
          .andExpect(status().isForbidden());
      mockMvc
          .perform(get("/api/v1/admin/search/reindex/{id}", UUID.randomUUID()).with(as(staff)))
          .andExpect(status().isForbidden());
    }
  }

  @Test
  @DisplayName("anything not listed is denied, even for an admin")
  void unlistedPathsAreDenied() throws Exception {
    mockMvc
        .perform(get("/api/v1/search/other").with(as(Role.ADMIN)))
        .andExpect(status().isForbidden());
    mockMvc
        .perform(post("/api/v1/search/bags").with(as(Role.ADMIN)))
        .andExpect(status().isForbidden());
  }

  @Test
  @DisplayName("health needs no token")
  void healthIsOpen() throws Exception {
    mockMvc.perform(get("/actuator/health")).andExpect(status().isOk());
  }
}
