package com.aerotrace.journey.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aerotrace.common.security.Role;
import com.aerotrace.journey.base.AbstractApiTest;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * AT-15: a passenger reads only their own bag (anything else is a 404, exactly like an unknown
 * tag), staff read every bag. Plus the role by endpoint matrix, and the 401 / 403 split.
 */
class OwnershipAndRolesTest extends AbstractApiTest {

  @Test
  void at15_aPassengerSeesTheirOwnBagAndNobodyElses() throws Exception {
    String alice = newPassengerRef();
    String bob = newPassengerRef();
    String aliceBag = registeredBag(alice);

    journey(aliceBag, as(Role.PASSENGER, alice))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.bagTag").value(aliceBag));

    String stranger =
        journey(aliceBag, as(Role.PASSENGER, bob))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("NOT_FOUND"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    String unknown =
        journey(newBagTag(), as(Role.PASSENGER, bob))
            .andExpect(status().isNotFound())
            .andReturn()
            .getResponse()
            .getContentAsString();
    // The same answer, so a passenger cannot tell "not yours" from "does not exist".
    assertThat(json.readTree(stranger).path("message"))
        .isEqualTo(json.readTree(unknown).path("message"));
    // (The error echoes the path the caller asked for, as every service does; nothing else.)
    assertThat(stranger).doesNotContain(alice);
  }

  @Test
  void at15_aPassengerTokenWithoutAReferenceSeesNoBag() throws Exception {
    String bag = registeredBag(newPassengerRef());

    journey(bag, as(Role.PASSENGER, null)).andExpect(status().isNotFound());
  }

  @Test
  void at15_staffSeeEveryBag() throws Exception {
    String bag = registeredBag(newPassengerRef());

    for (Role staff : new Role[] {Role.AGENT, Role.DISPATCHER, Role.ADMIN}) {
      journey(bag, as(staff)).andExpect(status().isOk());
    }
  }

  @Test
  void theRoleByEndpointMatrixIsEnforced() throws Exception {
    String passenger = newPassengerRef();
    String bag = registeredBag(passenger);
    Map<String, Object> scanBody = barcodeScan(bag, "IST", "CHECK_IN", 1, minutesAgo(1));

    // POST /bags: AGENT and ADMIN only.
    assertRegister(Role.PASSENGER, 403);
    assertRegister(Role.DISPATCHER, 403);
    assertRegister(Role.AGENT, 201);
    assertRegister(Role.ADMIN, 201);

    // POST /scans: staff only, never a passenger.
    assertScan(scanBody, Role.PASSENGER, 403);
    assertScan(scanBody, Role.AGENT, 202);
    assertScan(barcodeScan(bag, "IST", "SORTING", 2, minutesAgo(1)), Role.DISPATCHER, 202);
    assertScan(barcodeScan(bag, "IST", "LOADED", 3, minutesAgo(1)), Role.ADMIN, 202);

    // GET journey: everyone with a role (a passenger only for their own bag, tested above).
    for (Role role : Role.values()) {
      journey(bag, as(role, passenger)).andExpect(status().isOk());
    }
  }

  @Test
  void noTokenIs401WithABearerChallengeAndAWrongRoleIs403() throws Exception {
    String bag = registeredBag(newPassengerRef());

    mockMvc
        .perform(get("/api/v1/bags/{bagTag}/journey", bag))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    mockMvc
        .perform(
            post("/api/v1/scans")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}")
                .header("Idempotency-Key", newKey()))
        .andExpect(status().isUnauthorized());
    mockMvc
        .perform(
            post("/api/v1/bags")
                .with(as(Role.PASSENGER, newPassengerRef()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
  }

  @Test
  void anythingNotListedIsDeniedAndTheHealthProbeIsOpen() throws Exception {
    mockMvc.perform(get("/api/v1/other").with(as(Role.ADMIN))).andExpect(status().isForbidden());
    mockMvc.perform(get("/actuator/health")).andExpect(status().isOk());
    mockMvc.perform(get("/actuator/env").with(as(Role.ADMIN))).andExpect(status().isForbidden());
  }

  @Test
  void aMalformedBagTagInThePathIsA400() throws Exception {
    journey("12345", as(Role.AGENT))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
  }

  private void assertRegister(Role role, int expected) throws Exception {
    RequestPostProcessor caller = as(role, newPassengerRef());
    assertThat(
            registerBag(registration(newBagTag(), newPassengerRef()), caller)
                .andReturn()
                .getResponse()
                .getStatus())
        .as("POST /bags as %s", role)
        .isEqualTo(expected);
  }

  private void assertScan(Map<String, Object> body, Role role, int expected) throws Exception {
    assertThat(
            scan(body, newKey(), as(role, newPassengerRef())).andReturn().getResponse().getStatus())
        .as("POST /scans as %s", role)
        .isEqualTo(expected);
  }
}
