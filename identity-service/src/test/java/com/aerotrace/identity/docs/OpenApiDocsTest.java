package com.aerotrace.identity.docs;

import static org.assertj.core.api.Assertions.assertThat;

import com.aerotrace.identity.support.IdentityTestBase;
import java.net.http.HttpResponse;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The OpenAPI definition the gateway serves in its Swagger UI. The 401/403/400 examples in it are
 * compared with what the service really answers, so the documentation cannot drift from the code.
 */
@TestPropertySource(
    properties = {
      "aerotrace.identity.seed.enabled=true",
      "aerotrace.identity.seed.password=seed-test-password-1"
    })
class OpenApiDocsTest extends IdentityTestBase {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final List<String> PROBLEM_FIELDS = List.of("code", "message", "status", "path");

  private JsonNode docs() {
    HttpResponse<String> response = get("/v3/api-docs");
    assertThat(response.statusCode()).isEqualTo(200);
    return JSON.readTree(response.body());
  }

  private JsonNode operation(JsonNode docs, String path, String method) {
    return docs.get("paths").get(path).get(method);
  }

  /** The example attached to a response, whether the docs hold it as an object or as text. */
  private JsonNode example(JsonNode docs, String path, String method, String status) {
    JsonNode examples =
        operation(docs, path, method)
            .get("responses")
            .get(status)
            .get("content")
            .get("application/json")
            .get("examples");
    assertThat(examples).as(method + " " + path + " " + status + " examples").isNotNull();
    JsonNode value = examples.values().iterator().next().get("value");
    return value.isString() ? JSON.readTree(value.asString()) : value;
  }

  private String token(String username) {
    return JSON.readTree(
            postJson(
                    "/api/v1/auth/token",
                    "{\"username\":\"" + username + "\",\"password\":\"seed-test-password-1\"}")
                .body())
        .get("access_token")
        .asString();
  }

  private void assertSameShapeAndValues(JsonNode example, JsonNode actual) {
    Set<String> exampleKeys = new HashSet<>(example.propertyNames());
    Set<String> actualKeys = new HashSet<>(actual.propertyNames());
    assertThat(exampleKeys).isEqualTo(actualKeys);
    for (String field : PROBLEM_FIELDS) {
      assertThat(example.get(field)).as(field).isEqualTo(actual.get(field));
    }
  }

  @Test
  void definitionIsServedWithoutAToken() {
    assertThat(get("/v3/api-docs").statusCode()).isEqualTo(200);
    assertThat(send("GET", "/v3/api-docs", null, "stale.or.bad.token").statusCode()).isEqualTo(200);
  }

  @Test
  void describesEveryEndpoint() {
    JsonNode paths = docs().get("paths");

    assertThat(paths.propertyNames())
        .contains(
            "/api/v1/auth/token",
            "/api/v1/auth/me",
            "/api/v1/auth/policy",
            "/api/v1/auth/keys/rotate",
            "/.well-known/jwks.json");
    assertThat(docs().get("info").get("title").asString()).isEqualTo("AeroTrace Identity Service");
  }

  @Test
  void declaresBearerTokensAndRequiresThemOnlyWhereTheyAreNeeded() {
    JsonNode docs = docs();

    JsonNode scheme = docs.get("components").get("securitySchemes").get("bearerAuth");
    assertThat(scheme.get("type").asString()).isEqualTo("http");
    assertThat(scheme.get("scheme").asString()).isEqualTo("bearer");
    assertThat(scheme.get("bearerFormat").asString()).isEqualTo("JWT");
    assertThat(operation(docs, "/api/v1/auth/me", "get").get("security")).isNotNull();
    assertThat(operation(docs, "/api/v1/auth/policy", "get").get("security")).isNotNull();
    assertThat(operation(docs, "/api/v1/auth/keys/rotate", "post").get("security")).isNotNull();
    for (String[] open :
        new String[][] {{"/api/v1/auth/token", "post"}, {"/.well-known/jwks.json", "get"}}) {
      JsonNode security = operation(docs, open[0], open[1]).get("security");
      assertThat(security == null || security.isEmpty()).as(open[0]).isTrue();
    }
  }

  @Test
  void everyProtectedEndpointDocuments401And403WithExamples() {
    JsonNode docs = docs();

    for (String[] secured :
        new String[][] {
          {"/api/v1/auth/me", "get"},
          {"/api/v1/auth/policy", "get"},
          {"/api/v1/auth/keys/rotate", "post"}
        }) {
      assertThat(example(docs, secured[0], secured[1], "401").get("code").asString())
          .as(secured[0])
          .isEqualTo("UNAUTHENTICATED");
      assertThat(example(docs, secured[0], secured[1], "403").get("code").asString())
          .as(secured[0])
          .isEqualTo("ACCESS_DENIED");
    }
  }

  @Test
  void the401ExampleMatchesARealUnauthenticatedResponse() {
    HttpResponse<String> actual = get("/api/v1/auth/me");

    assertThat(actual.statusCode()).isEqualTo(401);
    assertSameShapeAndValues(
        example(docs(), "/api/v1/auth/me", "get", "401"), JSON.readTree(actual.body()));
  }

  @Test
  void the403ExampleMatchesARealForbiddenResponse() {
    HttpResponse<String> actual =
        send("POST", "/api/v1/auth/keys/rotate", null, token("passenger"));

    assertThat(actual.statusCode()).isEqualTo(403);
    assertSameShapeAndValues(
        example(docs(), "/api/v1/auth/keys/rotate", "post", "403"), JSON.readTree(actual.body()));
  }

  @Test
  void theTokenEndpointDocumentsSuccessAndItsTwoFailures() {
    JsonNode docs = docs();
    JsonNode responses = operation(docs, "/api/v1/auth/token", "post").get("responses");

    assertThat(responses.propertyNames()).contains("200", "400", "401");
    HttpResponse<String> invalid = postJson("/api/v1/auth/token", "{}");
    HttpResponse<String> wrong =
        postJson(
            "/api/v1/auth/token", "{\"username\":\"agent\",\"password\":\"not-the-password\"}");
    assertThat(invalid.statusCode()).isEqualTo(400);
    assertThat(wrong.statusCode()).isEqualTo(401);
    assertSameShapeAndValues(
        example(docs, "/api/v1/auth/token", "post", "400"), JSON.readTree(invalid.body()));
    assertSameShapeAndValues(
        example(docs, "/api/v1/auth/token", "post", "401"), JSON.readTree(wrong.body()));
  }

  @Test
  void theSuccessExampleShowsTheTokenShapeWithoutARealToken() {
    String body =
        operation(docs(), "/api/v1/auth/token", "post").get("responses").get("200").toString();

    assertThat(body).contains("access_token").contains("token_type").contains("expires_in");
    // A placeholder, not something that looks like a usable signed token.
    assertThat(body).doesNotContainPattern("eyJ[\\w-]{10,}\\.[\\w-]{10,}\\.[\\w-]{10,}");
  }

  @Test
  void noExampleContainsACredentialOrPersonalData() {
    String all = get("/v3/api-docs").body();

    assertThat(all).doesNotContain("seed-test-password").doesNotContain("P-0000");
  }

  @Test
  void theSwaggerUiIsNotServedHereOnlyThroughTheGateway() {
    assertThat(get("/swagger-ui.html").statusCode()).isIn(401, 404);
    assertThat(get("/swagger-ui/index.html").statusCode()).isIn(401, 404);
  }
}
