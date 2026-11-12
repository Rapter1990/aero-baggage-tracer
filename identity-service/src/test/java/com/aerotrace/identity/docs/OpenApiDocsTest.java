package com.aerotrace.identity.docs;

import static org.assertj.core.api.Assertions.assertThat;

import com.aerotrace.identity.base.AbstractIntegrationTest;
import java.net.http.HttpResponse;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The OpenAPI definition the gateway serves in its Swagger UI. The error examples in it are
 * compared with what the service really answers, so the documentation cannot drift from the code.
 */
@TestPropertySource(properties = "aerotrace.identity.docs.enabled=true")
class OpenApiDocsTest extends AbstractIntegrationTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final List<String> PROBLEM_FIELDS = List.of("code", "message", "status", "path");
  private static final List<String> AUTH_PATHS =
      List.of(
          "/api/v1/auth/register",
          "/api/v1/auth/login",
          "/api/v1/auth/refresh-token",
          "/api/v1/auth/forgot-password",
          "/api/v1/auth/reset-password",
          "/api/v1/auth/logout");

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
  void describesTheSixAuthEndpointsAndTheKeySet() {
    JsonNode docs = docs();

    assertThat(docs.get("paths").propertyNames())
        .containsAll(AUTH_PATHS)
        .contains("/.well-known/jwks.json")
        .doesNotContain("/api/v1/auth/token", "/api/v1/auth/me", "/api/v1/auth/policy");
    assertThat(docs.get("info").get("title").asString()).isEqualTo("AeroTrace Identity Service");
  }

  @Test
  void declaresBearerTokensAndRequiresThemOnlyForStaffRegistration() {
    JsonNode docs = docs();

    JsonNode scheme = docs.get("components").get("securitySchemes").get("bearerAuth");
    assertThat(scheme.get("type").asString()).isEqualTo("http");
    assertThat(scheme.get("scheme").asString()).isEqualTo("bearer");
    assertThat(scheme.get("bearerFormat").asString()).isEqualTo("JWT");
    assertThat(operation(docs, "/api/v1/auth/register", "post").get("security")).isNotNull();
    for (String path : AUTH_PATHS.subList(1, AUTH_PATHS.size())) {
      JsonNode security = operation(docs, path, "post").get("security");
      assertThat(security == null || security.isEmpty()).as(path).isTrue();
    }
    JsonNode jwks = operation(docs, "/.well-known/jwks.json", "get").get("security");
    assertThat(jwks == null || jwks.isEmpty()).isTrue();
  }

  @Test
  void registrationDocuments401And403BecauseAStaffRoleNeedsAnAdmin() {
    JsonNode docs = docs();

    assertThat(example(docs, "/api/v1/auth/register", "post", "401").get("code").asString())
        .isEqualTo("UNAUTHENTICATED");
    assertThat(example(docs, "/api/v1/auth/register", "post", "403").get("code").asString())
        .isEqualTo("ACCESS_DENIED");
  }

  @Test
  void the401ExampleMatchesARealUnauthenticatedResponse() {
    HttpResponse<String> actual =
        postJson(
            "/api/v1/auth/register",
            body(
                "username",
                "doc-" + unique(),
                "email",
                "doc-" + unique() + "@example.com",
                "password",
                PASSWORD,
                "fullName",
                "Docs",
                "role",
                "AGENT"));

    assertThat(actual.statusCode()).isEqualTo(401);
    assertSameShapeAndValues(
        example(docs(), "/api/v1/auth/register", "post", "401"), JSON.readTree(actual.body()));
  }

  @Test
  void theLoginEndpointDocumentsSuccessAndItsTwoFailures() {
    JsonNode docs = docs();
    JsonNode responses = operation(docs, "/api/v1/auth/login", "post").get("responses");

    assertThat(responses.propertyNames()).contains("200", "400", "401");
    HttpResponse<String> invalid = postJson("/api/v1/auth/login", "{}");
    HttpResponse<String> wrong =
        postJson(
            "/api/v1/auth/login",
            body("usernameOrEmail", "nobody-" + unique(), "password", "not-the-password"));
    assertThat(invalid.statusCode()).isEqualTo(400);
    assertThat(wrong.statusCode()).isEqualTo(401);
    assertSameShapeAndValues(
        example(docs, "/api/v1/auth/login", "post", "400"), JSON.readTree(invalid.body()));
    assertSameShapeAndValues(
        example(docs, "/api/v1/auth/login", "post", "401"), JSON.readTree(wrong.body()));
  }

  @Test
  void theOtherErrorExamplesMatchRealResponses() {
    JsonNode docs = docs();
    String name = registerPassenger();

    HttpResponse<String> duplicate =
        postJson(
            "/api/v1/auth/register",
            body(
                "username",
                name,
                "email",
                "x-" + unique() + "@example.com",
                "password",
                PASSWORD,
                "fullName",
                "Dup"));
    HttpResponse<String> weak =
        postJson(
            "/api/v1/auth/register",
            body(
                "username",
                "weak-" + unique(),
                "email",
                "weak-" + unique() + "@example.com",
                "password",
                "short",
                "fullName",
                "Weak"));
    HttpResponse<String> badRefresh =
        postJson("/api/v1/auth/refresh-token", body("refreshToken", "never-issued"));
    HttpResponse<String> badReset =
        postJson(
            "/api/v1/auth/reset-password",
            body("resetToken", "never-issued", "newPassword", "a-brand-new-password"));

    assertSameShapeAndValues(
        example(docs, "/api/v1/auth/register", "post", "409"), JSON.readTree(duplicate.body()));
    assertSameShapeAndValues(
        example(docs, "/api/v1/auth/register", "post", "400"), JSON.readTree(weak.body()));
    assertSameShapeAndValues(
        example(docs, "/api/v1/auth/refresh-token", "post", "401"),
        JSON.readTree(badRefresh.body()));
    assertSameShapeAndValues(
        example(docs, "/api/v1/auth/reset-password", "post", "400"),
        JSON.readTree(badReset.body()));
  }

  @Test
  void theSuccessExampleShowsTheTokenShapeWithoutARealToken() {
    String body =
        operation(docs(), "/api/v1/auth/login", "post").get("responses").get("200").toString();

    assertThat(body)
        .contains("success")
        .contains("accessToken")
        .contains("refreshToken")
        .contains("tokenType");
    // A placeholder, not something that looks like a usable signed token.
    assertThat(body).doesNotContainPattern("eyJ[\\w-]{10,}\\.[\\w-]{10,}\\.[\\w-]{10,}");
  }

  @Test
  void noExampleContainsACredentialOrPersonalData() {
    String all = get("/v3/api-docs").body();

    assertThat(all).doesNotContain(PASSWORD).doesNotContain("P-0000");
  }

  @Test
  void theSwaggerUiIsNotServedHereOnlyThroughTheGateway() {
    assertThat(get("/swagger-ui.html").statusCode()).isIn(401, 404);
    assertThat(get("/swagger-ui/index.html").statusCode()).isIn(401, 404);
  }
}
