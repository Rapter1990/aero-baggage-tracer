package com.aerotrace.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.aerotrace.identity.base.AbstractIntegrationTest;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.json.JsonMapper;

/** Spring Data auditing: a user row records who created it and when, from the caller's token. */
class AuditColumnsTest extends AbstractIntegrationTest {

  private static final String DUMMY_PASSWORD = "dummy-password-1";

  @Autowired private JdbcClient jdbc;

  @Test
  @DisplayName("a user created by an admin records the subject of the admin's token")
  void createdUserRecordsTheCreator() {
    String token = login("dummy.admin", DUMMY_PASSWORD).get("accessToken").asString();
    String name = "audited-" + unique();

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
                "Audited",
                "role",
                "AGENT"),
            token);
    assertThat(response.statusCode()).isEqualTo(201);

    String creator = subjectOf(token);
    var row =
        jdbc.sql(
                "select created_by, created_at, updated_by, updated_at from identity.app_user"
                    + " where username = :name")
            .param("name", name)
            .query(
                (rs, rowNum) ->
                    new String[] {
                      rs.getString("created_by"),
                      rs.getString("updated_by"),
                      String.valueOf(
                          rs.getTimestamp("created_at")
                              .toInstant()
                              .equals(rs.getTimestamp("updated_at").toInstant()))
                    })
            .single();
    assertThat(row[0]).isEqualTo(creator);
    assertThat(row[1]).isEqualTo(creator);
    // Created in one go: the last change is the creation.
    assertThat(row[2]).isEqualTo("true");
    assertThat(creator).isNotBlank().isNotEqualTo("system").isNotEqualTo("anonymous");
  }

  @Test
  @DisplayName("users that existed before auditing, the seed data, are 'system'")
  void seedUsersAreSystem() {
    String createdBy =
        jdbc.sql("select created_by from identity.app_user where username = 'dummy.admin'")
            .query(String.class)
            .single();

    assertThat(createdBy).isEqualTo("system");
  }

  /** The {@code sub} claim of a token; the payload is read, the signature is not the point here. */
  private static String subjectOf(String token) {
    String payload = token.split("\\.")[1];
    String json = new String(Base64.getUrlDecoder().decode(payload), StandardCharsets.UTF_8);
    return JsonMapper.builder().build().readTree(json).get("sub").asString();
  }
}
