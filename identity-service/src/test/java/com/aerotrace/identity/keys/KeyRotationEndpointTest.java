package com.aerotrace.identity.keys;

import static org.assertj.core.api.Assertions.assertThat;

import com.aerotrace.identity.support.IdentityTestBase;
import com.aerotrace.identity.user.UserService;
import java.net.http.HttpResponse;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.json.JsonMapper;

/** An admin can introduce a new signing key on demand, and the request is attributable. */
@TestPropertySource(
    properties = {
      "aerotrace.identity.seed.enabled=true",
      "aerotrace.identity.seed.password=seed-test-password-1",
      "aerotrace.identity.keys.cache-ttl=0s",
      "aerotrace.identity.keys.publish-lead=10m"
    })
class KeyRotationEndpointTest extends IdentityTestBase {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Autowired private SigningKeyService keys;
  @Autowired private UserService users;
  @Autowired private JdbcClient jdbc;

  @AfterEach
  void restoreSingleKey() {
    jdbc.sql("DELETE FROM identity.signing_key").update();
  }

  private String login(String username) {
    return JSON.readTree(
            postJson(
                    "/api/v1/auth/token",
                    "{\"username\":\"" + username + "\",\"password\":\"seed-test-password-1\"}")
                .body())
        .get("access_token")
        .asString();
  }

  @Test
  void anAdminRotationPublishesANewKeyAndReturnsItsId() {
    jdbc.sql("DELETE FROM identity.signing_key").update();
    keys.ensureKeyExists();
    String before = keys.signingKey().kid();

    HttpResponse<String> response = send("POST", "/api/v1/auth/keys/rotate", null, login("admin"));

    assertThat(response.statusCode()).isEqualTo(202);
    String kid = JSON.readTree(response.body()).get("kid").asString();
    assertThat(kid).isNotEqualTo(before);
    assertThat(keys.publishedKeys().getKeyByKeyId(kid)).isNotNull();
    assertThat(keys.publishedKeys().getKeyByKeyId(before)).isNotNull();
  }

  @Test
  void askingAgainBeforeTheNewKeyActivatesDoesNotStackKeys() {
    String token = login("admin");

    String first =
        JSON.readTree(send("POST", "/api/v1/auth/keys/rotate", null, token).body())
            .get("kid")
            .asString();
    String second =
        JSON.readTree(send("POST", "/api/v1/auth/keys/rotate", null, token).body())
            .get("kid")
            .asString();

    assertThat(second).isEqualTo(first);
  }

  @Test
  void theRequestIsAuditedWithTheAdminWhoMadeIt() {
    UUID admin = users.findByUsername("admin").orElseThrow().id();
    int before = audited(admin);

    send("POST", "/api/v1/auth/keys/rotate", null, login("admin"));

    assertThat(audited(admin)).isEqualTo(before + 1);
  }

  private int audited(UUID userId) {
    return jdbc.sql(
            "SELECT count(*) FROM identity.auth_audit "
                + "WHERE event_type = 'KEY_ROTATION_REQUESTED' AND user_id = :id")
        .param("id", userId)
        .query(Integer.class)
        .single();
  }

  @Test
  void aRefusedRotationChangesNothing() {
    jdbc.sql("DELETE FROM identity.signing_key").update();
    keys.ensureKeyExists();
    int rows = rows();

    HttpResponse<String> response =
        send("POST", "/api/v1/auth/keys/rotate", null, login("dispatcher"));

    assertThat(response.statusCode()).isEqualTo(403);
    assertThat(rows()).isEqualTo(rows);
  }

  private int rows() {
    return jdbc.sql("SELECT count(*) FROM identity.signing_key").query(Integer.class).single();
  }
}
