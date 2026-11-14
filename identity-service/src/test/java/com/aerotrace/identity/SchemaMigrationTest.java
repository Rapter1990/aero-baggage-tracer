package com.aerotrace.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aerotrace.identity.base.AbstractIntegrationTest;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;

/** The Liquibase changelog creates the identity schema, and the database enforces its rules. */
class SchemaMigrationTest extends AbstractIntegrationTest {

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

  @Test
  void createsTheServiceOwnedTables() {
    assertThat(columns("app_user"))
        .contains(
            "id",
            "username",
            "email",
            "full_name",
            "password_hash",
            "passenger_ref",
            "enabled",
            "created_at");
    assertThat(columns("refresh_token"))
        .contains("id", "user_id", "token_hash", "expires_at", "revoked_at", "created_at");
    assertThat(columns("password_reset_token"))
        .contains("id", "user_id", "token_hash", "expires_at", "used_at", "created_at");
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
  void emailsAreUniqueAndStoredInLowerCase() {
    String email = "dup-" + unique() + "@example.com";
    setEmail(insertUser("mail-a-" + unique(), null), email);
    UUID other = insertUser("mail-b-" + unique(), null);

    assertThatThrownBy(() -> setEmail(other, email)).isInstanceOf(DataAccessException.class);
    assertThatThrownBy(() -> setEmail(other, "Upper-" + unique() + "@example.com"))
        .isInstanceOf(DataAccessException.class);
  }

  @Test
  void tokenHashesAreUniqueAndATokenDiesWithItsUser() {
    UUID user = insertUser("tok-" + unique(), null);
    String hash = unique().repeat(8);
    insertRefreshToken(user, hash);

    assertThatThrownBy(() -> insertRefreshToken(user, hash))
        .isInstanceOf(DataAccessException.class);

    jdbc.sql("delete from identity.app_user where id = :id").param("id", user).update();
    assertThat(
            jdbc.sql("select count(*) from identity.refresh_token where token_hash = :h")
                .param("h", hash)
                .query(Integer.class)
                .single())
        .isZero();
  }

  private void setEmail(UUID id, String email) {
    jdbc.sql("update identity.app_user set email = :e where id = :id")
        .param("e", email)
        .param("id", id)
        .update();
  }

  private void insertRefreshToken(UUID user, String hash) {
    jdbc.sql(
            "insert into identity.refresh_token (id, user_id, token_hash, expires_at, created_at) "
                + "values (gen_random_uuid(), :u, :h, now() + interval '1 day', now())")
        .param("u", user)
        .param("h", hash)
        .update();
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
