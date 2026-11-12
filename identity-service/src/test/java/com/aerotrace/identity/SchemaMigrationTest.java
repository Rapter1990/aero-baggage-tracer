package com.aerotrace.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aerotrace.identity.support.IdentityTestBase;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;

/** The Liquibase changelog creates the identity schema, and the database enforces its rules. */
class SchemaMigrationTest extends IdentityTestBase {

  private static final String HASH = "$2a$04$abcdefghijklmnopqrstuuabcdefghijklmnopqrstuvwxyz01234";

  @Autowired private JdbcClient jdbc;

  private List<String> columns(String table) {
    return jdbc.sql(
            "select column_name from information_schema.columns "
                + "where table_schema = 'identity' and table_name = :t")
        .param("t", table)
        .query(String.class)
        .list();
  }

  private UUID insertUser(String username, String passengerRef) {
    UUID id = UUID.randomUUID();
    jdbc.sql(
            "insert into identity.app_user (id, username, password_hash, passenger_ref) "
                + "values (:id, :u, :h, :p)")
        .param("id", id)
        .param("u", username)
        .param("h", HASH)
        .param("p", passengerRef)
        .update();
    return id;
  }

  private static String unique() {
    return UUID.randomUUID().toString().substring(0, 8);
  }

  @Test
  void createsTheServiceOwnedTables() {
    assertThat(columns("app_user"))
        .contains("id", "username", "password_hash", "passenger_ref", "enabled", "created_at");
    assertThat(columns("user_role")).contains("user_id", "role");
    assertThat(columns("auth_audit"))
        .contains("id", "occurred_at", "event_type", "outcome", "username", "user_id");
  }

  @Test
  void theAuditTableHasNoColumnThatCouldHoldACredentialOrToken() {
    assertThat(columns("auth_audit"))
        .noneMatch(c -> c.contains("password") || c.contains("token") || c.contains("secret"));
  }

  @Test
  void usernamesAreUnique() {
    String name = "dup-" + unique();
    insertUser(name, null);

    assertThatThrownBy(() -> insertUser(name, null)).isInstanceOf(DataAccessException.class);
  }

  @Test
  void aPassengerRefBindsToAtMostOneUser() {
    String ref =
        "P-" + String.format("%020d", Math.abs(UUID.randomUUID().getMostSignificantBits()));
    insertUser("pax-a-" + unique(), ref);

    assertThatThrownBy(() -> insertUser("pax-b-" + unique(), ref))
        .isInstanceOf(DataAccessException.class);
  }

  @Test
  void aPassengerRefMustBePseudonymousInTheAgreedFormat() {
    assertThatThrownBy(() -> insertUser("bad-ref-" + unique(), "jane.doe@example.com"))
        .isInstanceOf(DataAccessException.class);
  }

  @Test
  void onlyKnownRolesCanBeAssigned() {
    UUID id = insertUser("role-" + unique(), null);

    assertThatThrownBy(
            () ->
                jdbc.sql("insert into identity.user_role (user_id, role) values (:id, 'ROOT')")
                    .param("id", id)
                    .update())
        .isInstanceOf(DataAccessException.class);
  }

  @Test
  void aUserCannotHoldTheSameRoleTwice() {
    UUID id = insertUser("twice-" + unique(), null);
    jdbc.sql("insert into identity.user_role (user_id, role) values (:id, 'AGENT')")
        .param("id", id)
        .update();

    assertThatThrownBy(
            () ->
                jdbc.sql("insert into identity.user_role (user_id, role) values (:id, 'AGENT')")
                    .param("id", id)
                    .update())
        .isInstanceOf(DataAccessException.class);
  }

  @Test
  void auditEntriesCanBeAddedButNeverChangedOrRemoved() {
    jdbc.sql(
            "insert into identity.auth_audit (event_type, outcome, username) "
                + "values ('TOKEN_ISSUED', 'SUCCESS', 'audit-immutable')")
        .update();

    assertThatThrownBy(
            () ->
                jdbc.sql(
                        "update identity.auth_audit set outcome = 'FAILURE' "
                            + "where username = 'audit-immutable'")
                    .update())
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(
            () ->
                jdbc.sql("delete from identity.auth_audit where username = 'audit-immutable'")
                    .update())
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(() -> jdbc.sql("truncate identity.auth_audit").update())
        .isInstanceOf(DataAccessException.class);
  }
}
