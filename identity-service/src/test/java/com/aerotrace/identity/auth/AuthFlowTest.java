package com.aerotrace.identity.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.aerotrace.identity.base.AbstractIntegrationTest;
import com.aerotrace.identity.security.jwt.JwtTokenService;
import io.jsonwebtoken.Claims;
import java.net.http.HttpResponse;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;

/**
 * The whole authentication API over HTTP against a real database: register, login, refresh-token,
 * forgot-password, reset-password and logout, and the first administrator created at start-up.
 */
@TestPropertySource(
    properties = {
      "aerotrace.identity.auth.password-reset-return-token=true",
      "aerotrace.identity.bootstrap-admin.username=Root-Admin",
      "aerotrace.identity.bootstrap-admin.password=bootstrap-admin-password",
      "aerotrace.identity.bootstrap-admin.email=root@example.com"
    })
class AuthFlowTest extends AbstractIntegrationTest {

  private static final String REGISTER = "/api/v1/auth/register";
  private static final String LOGIN = "/api/v1/auth/login";
  private static final String REFRESH = "/api/v1/auth/refresh-token";
  private static final String FORGOT = "/api/v1/auth/forgot-password";
  private static final String RESET = "/api/v1/auth/reset-password";
  private static final String LOGOUT = "/api/v1/auth/logout";

  @Autowired private JwtTokenService tokens;
  @Autowired private JdbcClient jdbc;

  private HttpResponse<String> refresh(String refreshToken) {
    return postJson(REFRESH, body("refreshToken", refreshToken));
  }

  private String adminToken() {
    return login("root-admin", "bootstrap-admin-password").get("accessToken").asString();
  }

  // ---- register ----

  @Test
  void registersAPassengerWhoGetsAPassengerReferenceInTheirToken() {
    String name = "reg-" + unique();

    HttpResponse<String> response =
        postJson(
            REGISTER,
            body(
                "username",
                name.toUpperCase(),
                "email",
                name + "@Example.com",
                "password",
                PASSWORD,
                "fullName",
                "Reg User"));

    assertThat(response.statusCode()).isEqualTo(201);
    assertThat(response.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
    JsonNode data = json(response).get("data");
    assertThat(data.get("username").asString()).isEqualTo(name);
    assertThat(data.get("email").asString()).isEqualTo(name + "@example.com");
    assertThat(data.get("roles")).extracting(JsonNode::asString).containsExactly("PASSENGER");
    assertThat(data.get("status").asString()).isEqualTo("ACTIVE");
    assertThat(response.body()).doesNotContain("passengerRef").doesNotContain("password");

    Claims claims = tokens.parseClaims(login(name, PASSWORD).get("accessToken").asString());
    assertThat(claims.get("passengerRef", String.class)).matches("P-[0-9]{20}");
    assertThat(claims.getSubject()).isEqualTo(data.get("id").asString());
  }

  @Test
  void storesOnlyAHashOfThePassword() {
    String name = registerPassenger();

    String hash =
        jdbc.sql("select password_hash from identity.app_user where username = :u")
            .param("u", name)
            .query(String.class)
            .single();

    assertThat(hash).startsWith("$2").doesNotContain(PASSWORD);
  }

  @Test
  void refusesADuplicateUsernameOrEmailWith409() {
    String name = registerPassenger();

    HttpResponse<String> sameName =
        postJson(
            REGISTER,
            body(
                "username",
                name,
                "email",
                "other-" + unique() + "@example.com",
                "password",
                PASSWORD,
                "fullName",
                "Dup"));
    HttpResponse<String> sameEmail =
        postJson(
            REGISTER,
            body(
                "username",
                "other-" + unique(),
                "email",
                name + "@example.com",
                "password",
                PASSWORD,
                "fullName",
                "Dup"));

    for (HttpResponse<String> response : List.of(sameName, sameEmail)) {
      assertThat(response.statusCode()).isEqualTo(409);
      assertThat(json(response).get("code").asString()).isEqualTo("USER_ALREADY_EXISTS");
      assertThat(response.body()).doesNotContain(name);
    }
  }

  @Test
  void aStaffAccountNeedsAnAdminToken() {
    String name = "staff-" + unique();
    String request =
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
            "DISPATCHER");

    assertThat(postJson(REGISTER, request).statusCode()).isEqualTo(401);
    String passengerToken = login(registerPassenger(), PASSWORD).get("accessToken").asString();
    assertThat(postJson(REGISTER, request, passengerToken).statusCode()).isEqualTo(403);

    HttpResponse<String> created = postJson(REGISTER, request, adminToken());
    assertThat(created.statusCode()).isEqualTo(201);
    JsonNode staffTokens = login(name, PASSWORD);
    assertThat(staffTokens.get("roles"))
        .extracting(JsonNode::asString)
        .containsExactly("DISPATCHER");
    // Staff carry no passenger reference.
    assertThat(tokens.parseClaims(staffTokens.get("accessToken").asString()).get("passengerRef"))
        .isNull();
  }

  @Test
  void createsTheBootstrapAdministratorOnceAndTheyCanSignIn() {
    JsonNode admin = login("ROOT@example.com", "bootstrap-admin-password");

    assertThat(admin.get("roles")).extracting(JsonNode::asString).containsExactly("ADMIN");
    assertThat(
            jdbc.sql("select count(*) from identity.app_user where username = 'root-admin'")
                .query(Integer.class)
                .single())
        .isEqualTo(1);
  }

  // ---- login ----

  @Test
  void logsInWithEitherTheUsernameOrTheEmail() {
    String name = registerPassenger();

    JsonNode byName = login(name, PASSWORD);
    JsonNode byEmail = login(name.toUpperCase() + "@EXAMPLE.COM", PASSWORD);

    for (JsonNode data : List.of(byName, byEmail)) {
      assertThat(data.get("tokenType").asString()).isEqualTo("Bearer");
      assertThat(data.get("accessToken").asString()).isNotBlank();
      assertThat(data.get("refreshToken").asString()).hasSize(64);
      assertThat(data.get("accessTokenExpiresAt").asString()).isNotBlank();
      assertThat(data.get("refreshTokenExpiresAt").asString()).isNotBlank();
    }
  }

  @Test
  void answersAnUnknownUserAndAWrongPasswordIdentically() {
    String name = registerPassenger();

    HttpResponse<String> wrong =
        postJson(LOGIN, body("usernameOrEmail", name, "password", "wrong-password-1"));
    HttpResponse<String> unknown =
        postJson(
            LOGIN, body("usernameOrEmail", "ghost-" + unique(), "password", "wrong-password-1"));

    assertThat(wrong.statusCode()).isEqualTo(401);
    assertThat(unknown.statusCode()).isEqualTo(401);
    assertThat(json(wrong).get("code")).isEqualTo(json(unknown).get("code"));
    assertThat(json(wrong).get("message")).isEqualTo(json(unknown).get("message"));
  }

  @Test
  void refusesAPasswordThatOnlyStartsRight() {
    String name = "long-" + unique();
    String seventyTwo = "p".repeat(72);
    postJson(
        REGISTER,
        body(
            "username",
            name,
            "email",
            name + "@example.com",
            "password",
            seventyTwo,
            "fullName",
            "Long Password"));

    assertThat(postJson(LOGIN, body("usernameOrEmail", name, "password", seventyTwo)).statusCode())
        .isEqualTo(200);
    assertThat(
            postJson(LOGIN, body("usernameOrEmail", name, "password", seventyTwo + "junk"))
                .statusCode())
        .isEqualTo(401);
  }

  // ---- refresh ----

  @Test
  void rotatesTheRefreshTokenAndRefusesTheOldOne() {
    String name = registerPassenger();
    String first = login(name, PASSWORD).get("refreshToken").asString();

    HttpResponse<String> rotated = refresh(first);

    assertThat(rotated.statusCode()).isEqualTo(200);
    String second = json(rotated).get("data").get("refreshToken").asString();
    assertThat(second).isNotEqualTo(first);
    assertThat(refresh(first).statusCode()).isEqualTo(401);
  }

  @Test
  void reusingARefreshTokenEndsEverySessionOfThatUser() {
    String name = registerPassenger();
    String stolen = login(name, PASSWORD).get("refreshToken").asString();
    String legitimate = json(refresh(stolen)).get("data").get("refreshToken").asString();

    // The thief replays the token the user has already used.
    assertThat(refresh(stolen).statusCode()).isEqualTo(401);

    // The user's newer token is gone as well.
    assertThat(refresh(legitimate).statusCode()).isEqualTo(401);
    assertThat(
            jdbc.sql(
                    "select count(*) from identity.auth_audit "
                        + "where event_type = 'REFRESH_REUSE_DETECTED' and username = :u")
                .param("u", name)
                .query(Integer.class)
                .single())
        .isGreaterThanOrEqualTo(1);
  }

  @Test
  void refusesAnUnknownRefreshTokenWithoutRevealingWhy() {
    HttpResponse<String> response = refresh("this-token-was-never-issued");

    assertThat(response.statusCode()).isEqualTo(401);
    assertThat(json(response).get("message").asString())
        .isEqualTo("Invalid or expired refresh token.");
  }

  @Test
  void storesOnlyAHashOfTheRefreshToken() {
    String name = registerPassenger();
    String refreshToken = login(name, PASSWORD).get("refreshToken").asString();

    List<String> hashes =
        jdbc.sql(
                "select t.token_hash from identity.refresh_token t "
                    + "join identity.app_user u on u.id = t.user_id where u.username = :u")
            .param("u", name)
            .query(String.class)
            .list();

    assertThat(hashes).isNotEmpty().doesNotContain(refreshToken);
  }

  // ---- logout ----

  @Test
  void logoutRevokesTheRefreshTokenAndIsIdempotent() {
    String name = registerPassenger();
    String refreshToken = login(name, PASSWORD).get("refreshToken").asString();

    HttpResponse<String> first = postJson(LOGOUT, body("refreshToken", refreshToken));
    HttpResponse<String> again = postJson(LOGOUT, body("refreshToken", refreshToken));
    HttpResponse<String> unknown = postJson(LOGOUT, body("refreshToken", "never-issued"));

    for (HttpResponse<String> response : List.of(first, again, unknown)) {
      assertThat(response.statusCode()).isEqualTo(200);
      assertThat(json(response).get("data").get("message").asString())
          .isEqualTo("Logout completed successfully");
    }
    assertThat(refresh(refreshToken).statusCode()).isEqualTo(401);
  }

  // ---- forgot / reset ----

  @Test
  void resetsAPasswordOnceAndEndsEverySession() {
    String name = registerPassenger();
    String session = login(name, PASSWORD).get("refreshToken").asString();

    HttpResponse<String> forgot = postJson(FORGOT, body("usernameOrEmail", name));
    assertThat(forgot.statusCode()).isEqualTo(200);
    String resetToken = json(forgot).get("data").get("resetToken").asString();
    assertThat(resetToken).hasSize(64);

    String newPassword = "a-brand-new-password";
    HttpResponse<String> reset =
        postJson(RESET, body("resetToken", resetToken, "newPassword", newPassword));
    assertThat(reset.statusCode()).isEqualTo(200);

    assertThat(refresh(session).statusCode()).isEqualTo(401);
    assertThat(postJson(LOGIN, body("usernameOrEmail", name, "password", PASSWORD)).statusCode())
        .isEqualTo(401);
    assertThat(login(name, newPassword).get("accessToken").asString()).isNotBlank();
    // A reset token works once.
    assertThat(
            postJson(RESET, body("resetToken", resetToken, "newPassword", "yet-another-password"))
                .statusCode())
        .isEqualTo(400);
  }

  @Test
  void aNewResetRequestReplacesTheEarlierOne() {
    String name = registerPassenger();
    String first =
        json(postJson(FORGOT, body("usernameOrEmail", name)))
            .get("data")
            .get("resetToken")
            .asString();
    String second =
        json(postJson(FORGOT, body("usernameOrEmail", name)))
            .get("data")
            .get("resetToken")
            .asString();

    assertThat(
            postJson(RESET, body("resetToken", first, "newPassword", "a-brand-new-password"))
                .statusCode())
        .isEqualTo(400);
    assertThat(
            postJson(RESET, body("resetToken", second, "newPassword", "a-brand-new-password"))
                .statusCode())
        .isEqualTo(200);
  }

  @Test
  void forgotPasswordAnswersTheSameWhetherOrNotTheAccountExistsExceptForTheDevelopmentToken() {
    HttpResponse<String> unknown = postJson(FORGOT, body("usernameOrEmail", "ghost-" + unique()));

    assertThat(unknown.statusCode()).isEqualTo(200);
    assertThat(json(unknown).get("data").get("message").asString())
        .isEqualTo("If the account exists, a password reset has been started.");
    assertThat(json(unknown).get("data").has("resetToken")).isFalse();
  }

  @Test
  void refusesAnUnknownResetToken() {
    HttpResponse<String> response =
        postJson(RESET, body("resetToken", "never-issued", "newPassword", "a-brand-new-password"));

    assertThat(response.statusCode()).isEqualTo(400);
    assertThat(json(response).get("message").asString())
        .isEqualTo("Invalid or expired password reset token.");
  }

  // ---- audit ----

  @Test
  void recordsTheEventsWithoutAnySecret() {
    String name = registerPassenger();
    JsonNode data = login(name, PASSWORD);
    postJson(LOGIN, body("usernameOrEmail", name, "password", "wrong-password-1"));

    List<String> events =
        jdbc.sql("select event_type from identity.auth_audit where username = :u")
            .param("u", name)
            .query(String.class)
            .list();

    assertThat(events).contains("USER_REGISTERED", "TOKEN_ISSUED", "LOGIN_FAILED");
    String everything =
        String.join(
            " ",
            jdbc.sql(
                    "select concat_ws(' ', event_type, outcome, username) from identity.auth_audit")
                .query(String.class)
                .list());
    assertThat(everything)
        .doesNotContain(PASSWORD)
        .doesNotContain(data.get("accessToken").asString())
        .doesNotContain(data.get("refreshToken").asString());
  }
}
