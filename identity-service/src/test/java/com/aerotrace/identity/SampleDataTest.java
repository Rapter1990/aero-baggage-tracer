package com.aerotrace.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.aerotrace.identity.base.AbstractIntegrationTest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;

/** The dummy accounts of changeset 003 exist in the dev context and work like any other account. */
class SampleDataTest extends AbstractIntegrationTest {

  private static final String DUMMY_PASSWORD = "dummy-password-1";

  @Autowired private JdbcClient jdbc;

  @Test
  void createsOneAccountPerRoleAndASecondPassenger() {
    List<String> roles =
        jdbc.sql(
                "select r.role from identity.user_role r join identity.app_user u on u.id = r.user_id "
                    + "where u.username like 'dummy.%' order by r.role")
            .query(String.class)
            .list();

    assertThat(roles).containsExactly("ADMIN", "AGENT", "DISPATCHER", "PASSENGER", "PASSENGER");
  }

  @Test
  void theDummyAccountsCanSignInWithTheirRoles() {
    assertThat(roles(login("dummy.admin", DUMMY_PASSWORD))).containsExactly("ADMIN");
    assertThat(roles(login("dummy.agent@example.com", DUMMY_PASSWORD))).containsExactly("AGENT");
    assertThat(roles(login("dummy.dispatcher", DUMMY_PASSWORD))).containsExactly("DISPATCHER");
    assertThat(roles(login("dummy.passenger", DUMMY_PASSWORD))).containsExactly("PASSENGER");
  }

  @Test
  void onlyThePassengersHaveAPassengerReference() {
    List<String> withReference =
        jdbc.sql(
                "select username from identity.app_user "
                    + "where username like 'dummy.%' and passenger_ref is not null order by username")
            .query(String.class)
            .list();

    assertThat(withReference).containsExactly("dummy.passenger", "dummy.passenger2");
  }

  @Test
  void theDummyAdminCanCreateStaff() {
    String token = login("dummy.admin", DUMMY_PASSWORD).get("accessToken").asString();
    String name = "staff-" + unique();

    var response =
        postJson(
            "/api/v1/auth/register",
            body(
                "username",
                name,
                "email",
                name + "@example.com",
                "password",
                PASSWORD,
                "fullName",
                "Staff",
                "role",
                "AGENT"),
            token);

    assertThat(response.statusCode()).isEqualTo(201);
  }

  private static List<String> roles(JsonNode tokens) {
    return tokens.get("roles").valueStream().map(JsonNode::asString).toList();
  }
}
