package com.aerotrace.identity.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.aerotrace.identity.policy.AuthorizationPolicy;
import com.aerotrace.identity.support.IdentityTestBase;
import com.aerotrace.identity.user.UserService;
import java.net.http.HttpResponse;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** What the two read-only account endpoints return to an authenticated caller. */
@TestPropertySource(
    properties = {
      "aerotrace.identity.seed.enabled=true",
      "aerotrace.identity.seed.password=seed-test-password-1"
    })
class AccountEndpointsTest extends IdentityTestBase {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Autowired private UserService users;
  @Autowired private AuthorizationPolicy policy;

  private String login(String username) {
    return JSON.readTree(
            postJson(
                    "/api/v1/auth/token",
                    "{\"username\":\"" + username + "\",\"password\":\"seed-test-password-1\"}")
                .body())
        .get("data")
        .get("accessToken")
        .asString();
  }

  @Test
  void meReportsWhoTheTokenBelongsToAndNothingElse() {
    HttpResponse<String> response = send("GET", "/api/v1/auth/me", null, login("dispatcher"));

    assertThat(response.statusCode()).isEqualTo(200);
    JsonNode envelope = JSON.readTree(response.body());
    assertThat(envelope.get("success").asBoolean()).isTrue();
    JsonNode me = envelope.get("data");
    assertThat(me.get("subject").asString())
        .isEqualTo(users.findByUsername("dispatcher").orElseThrow().id().toString());
    assertThat(me.get("roles").get(0).asString()).isEqualTo("DISPATCHER");
    assertThat(me.size()).isEqualTo(2);
  }

  @Test
  void meDoesNotRevealThePassengerReferenceOrAnyPersonalData() {
    HttpResponse<String> response = send("GET", "/api/v1/auth/me", null, login("passenger"));

    assertThat(response.body()).doesNotContain("P-0").doesNotContain("passenger");
  }

  @Test
  void thePolicyEndpointPublishesTheCatalog() {
    HttpResponse<String> response = send("GET", "/api/v1/auth/policy", null, login("passenger"));

    assertThat(response.statusCode()).isEqualTo(200);
    JsonNode permissions = JSON.readTree(response.body()).get("data").get("permissions");
    List<String> names = permissions.valueStream().map(p -> p.get("name").asString()).toList();
    assertThat(names)
        .isEqualTo(policy.catalog().stream().map(AuthorizationPolicy.Entry::name).toList());
    JsonNode caseTransition = permissions.get(0);
    assertThat(caseTransition.get("name").asString()).isEqualTo("CASE_TRANSITION");
    assertThat(caseTransition.get("roles").valueStream().map(JsonNode::asString).toList())
        .containsExactly("AGENT", "DISPATCHER");
    assertThat(caseTransition.get("description").asString()).isNotBlank();
  }
}
