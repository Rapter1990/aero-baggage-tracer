package com.aerotrace.identity.token;

import static org.assertj.core.api.Assertions.assertThat;

import com.aerotrace.common.security.AeroTraceJwtValidators;
import com.aerotrace.common.security.JwtClaims;
import com.aerotrace.common.security.Role;
import com.aerotrace.identity.keys.SigningKeyService;
import com.aerotrace.identity.model.domain.AppUser;
import com.aerotrace.identity.support.IdentityTestBase;
import com.aerotrace.identity.user.UserService;
import com.nimbusds.jwt.SignedJWT;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.http.HttpResponse;
import java.text.ParseException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * POST /api/v1/auth/token end to end: the path the gateway forwards. Tokens are verified the way
 * the gateway does it, with its decoder settings and the shared claim validators, against this
 * service's live JWKS. Subclasses pick the signing algorithm.
 */
@ExtendWith(OutputCaptureExtension.class)
@TestPropertySource(
    properties = {
      "aerotrace.identity.seed.enabled=true",
      "aerotrace.identity.seed.password=seed-test-password-1",
      "aerotrace.identity.keys.cache-ttl=0s",
      "aerotrace.identity.keys.publish-lead=0s"
    })
abstract class AbstractTokenEndpointTest extends IdentityTestBase {

  static final String PATH = "/api/v1/auth/token";
  static final String PASSWORD = "seed-test-password-1";
  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Autowired UserService users;
  @Autowired JdbcClient jdbc;
  @Autowired SigningKeyService keys;
  @Autowired MeterRegistry meters;

  abstract String algorithm();

  @AfterEach
  void restoreSingleKey() {
    jdbc.sql("DELETE FROM identity.signing_key").update();
  }

  HttpResponse<String> login(String username, String password) {
    return postJson(PATH, "{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}");
  }

  String tokenFor(String username) {
    HttpResponse<String> response = login(username, PASSWORD);
    assertThat(response.statusCode()).isEqualTo(200);
    return JSON.readTree(response.body()).get("access_token").asString();
  }

  /** The decoder settings the api-gateway uses (see its SecurityConfig). */
  JwtDecoder gatewayDecoder(String issuer, String audience) {
    NimbusJwtDecoder decoder =
        NimbusJwtDecoder.withJwkSetUri("http://localhost:" + port() + "/.well-known/jwks.json")
            .jwsAlgorithms(
                algorithms -> {
                  algorithms.add(SignatureAlgorithm.RS256);
                  algorithms.add(SignatureAlgorithm.ES256);
                })
            .build();
    OAuth2TokenValidator<Jwt> validator = AeroTraceJwtValidators.create(issuer, audience);
    decoder.setJwtValidator(validator);
    return decoder;
  }

  JwtDecoder gatewayDecoder() {
    return gatewayDecoder("http://identity.test", "aerotrace-api");
  }

  // --- success ---------------------------------------------------------------------------

  @Test
  void validCredentialsReturnABearerToken() {
    HttpResponse<String> response = login("agent", PASSWORD);

    assertThat(response.statusCode()).isEqualTo(200);
    JsonNode body = JSON.readTree(response.body());
    assertThat(body.get("token_type").asString()).isEqualTo("Bearer");
    assertThat(body.get("expires_in").asLong()).isEqualTo(900);
    assertThat(body.get("access_token").asString()).matches("[\\w-]+\\.[\\w-]+\\.[\\w-]+");
    assertThat(response.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
  }

  @Test
  void theTokenCarriesTheAgreedClaims() throws ParseException {
    SignedJWT jwt = SignedJWT.parse(tokenFor("dispatcher"));
    AppUser user = users.findByUsername("dispatcher").orElseThrow();

    assertThat(jwt.getHeader().getAlgorithm().getName()).isEqualTo(algorithm());
    assertThat(jwt.getJWTClaimsSet().getIssuer()).isEqualTo("http://identity.test");
    assertThat(jwt.getJWTClaimsSet().getAudience()).containsExactly("aerotrace-api");
    assertThat(jwt.getJWTClaimsSet().getSubject()).isEqualTo(user.id().toString());
    assertThat(jwt.getJWTClaimsSet().getStringListClaim(JwtClaims.ROLES))
        .containsExactly("DISPATCHER");
    assertThat(jwt.getJWTClaimsSet().getJWTID()).isNotBlank();
    Instant issued = jwt.getJWTClaimsSet().getIssueTime().toInstant();
    Instant expires = jwt.getJWTClaimsSet().getExpirationTime().toInstant();
    assertThat(Duration.between(issued, expires)).isEqualTo(Duration.ofMinutes(15));
    assertThat(jwt.getJWTClaimsSet().getClaim(JwtClaims.PASSENGER_REF)).isNull();
  }

  @Test
  void aPassengerTokenCarriesThePseudonymousReference() throws ParseException {
    SignedJWT first = SignedJWT.parse(tokenFor("passenger"));
    SignedJWT second = SignedJWT.parse(tokenFor("passenger2"));

    assertThat(first.getJWTClaimsSet().getStringClaim(JwtClaims.PASSENGER_REF))
        .isEqualTo(users.findByUsername("passenger").orElseThrow().passengerRef())
        .matches("P-\\d{20}");
    assertThat(second.getJWTClaimsSet().getStringClaim(JwtClaims.PASSENGER_REF))
        .isNotEqualTo(first.getJWTClaimsSet().getStringClaim(JwtClaims.PASSENGER_REF));
    assertThat(first.getJWTClaimsSet().getStringListClaim(JwtClaims.ROLES))
        .containsExactly("PASSENGER");
  }

  @Test
  void usernamesAreCaseInsensitive() {
    assertThat(login("AGENT", PASSWORD).statusCode()).isEqualTo(200);
  }

  @Test
  void everyTokenHasItsOwnId() throws ParseException {
    String first = SignedJWT.parse(tokenFor("agent")).getJWTClaimsSet().getJWTID();
    String second = SignedJWT.parse(tokenFor("agent")).getJWTClaimsSet().getJWTID();

    assertThat(first).isNotEqualTo(second);
  }

  // --- what a resource server (the gateway) does with it ------------------------------------

  @Test
  void theGatewayAcceptsTheToken() {
    Jwt jwt = gatewayDecoder().decode(tokenFor("admin"));

    assertThat(jwt.getClaimAsStringList(JwtClaims.ROLES)).containsExactly("ADMIN");
    assertThat(Role.fromClaim(jwt.getClaimAsStringList(JwtClaims.ROLES).get(0)))
        .contains(Role.ADMIN);
  }

  @Test
  void aResourceServerForAnotherAudienceOrIssuerRejectsIt() {
    String token = tokenFor("agent");

    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> gatewayDecoder("http://identity.test", "another-api").decode(token))
        .isInstanceOf(BadJwtException.class);
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> gatewayDecoder("http://someone-else", "aerotrace-api").decode(token))
        .isInstanceOf(BadJwtException.class);
  }

  @Test
  void rotatingTheKeyKeepsEarlierTokensValidForTheGateway() {
    JwtDecoder gateway = gatewayDecoder();
    String before = tokenFor("agent");
    assertThat(gateway.decode(before)).isNotNull();

    keys.rotate();
    String after = tokenFor("agent");

    assertThat(gateway.decode(after)).isNotNull();
    assertThat(gateway.decode(before)).isNotNull();
    assertThat(kid(after)).isNotEqualTo(kid(before));
  }

  private static String kid(String token) {
    try {
      return SignedJWT.parse(token).getHeader().getKeyID();
    } catch (ParseException e) {
      throw new IllegalStateException(e);
    }
  }

  // --- failure -----------------------------------------------------------------------------

  @Test
  void aWrongPasswordIsRefusedWithAProblemResponse() {
    HttpResponse<String> response = login("agent", "not-the-right-password");

    assertThat(response.statusCode()).isEqualTo(401);
    JsonNode problem = JSON.readTree(response.body());
    assertThat(problem.get("code").asString()).isEqualTo("UNAUTHENTICATED");
    assertThat(problem.get("status").asInt()).isEqualTo(401);
    assertThat(response.body()).doesNotContain("access_token");
  }

  @Test
  void anUnknownUserGetsTheSameAnswerAsAWrongPassword() {
    HttpResponse<String> wrongPassword = login("agent", "not-the-right-password");
    HttpResponse<String> unknownUser = login("nobody-here", "not-the-right-password");

    assertThat(unknownUser.statusCode()).isEqualTo(wrongPassword.statusCode());
    assertThat(JSON.readTree(unknownUser.body()).get("message"))
        .isEqualTo(JSON.readTree(wrongPassword.body()).get("message"));
    assertThat(JSON.readTree(unknownUser.body()).get("code"))
        .isEqualTo(JSON.readTree(wrongPassword.body()).get("code"));
  }

  @Test
  void aDisabledUserCannotGetAToken() {
    String name = "disabled-" + UUID.randomUUID().toString().substring(0, 8);
    users.register(name, PASSWORD, Set.of(Role.AGENT), null);
    jdbc.sql("UPDATE identity.app_user SET enabled = false WHERE username = :u")
        .param("u", name)
        .update();

    assertThat(login(name, PASSWORD).statusCode()).isEqualTo(401);
  }

  @Test
  void incompleteOrOversizedRequestsAreRejectedAsInvalid() {
    for (String body :
        List.of(
            "{}",
            "{\"username\":\"agent\"}",
            "{\"password\":\"" + PASSWORD + "\"}",
            "{\"username\":\"\",\"password\":\"" + PASSWORD + "\"}",
            "{\"username\":\"agent\",\"password\":\"   \"}",
            "{\"username\":\"agent\",\"password\":\"" + "p".repeat(300) + "\"}",
            "{\"username\":\"" + "u".repeat(300) + "\",\"password\":\"" + PASSWORD + "\"}")) {
      HttpResponse<String> response = postJson(PATH, body);

      assertThat(response.statusCode())
          .as(body.substring(0, Math.min(40, body.length())))
          .isEqualTo(400);
      assertThat(JSON.readTree(response.body()).get("code").asString())
          .isEqualTo("VALIDATION_ERROR");
    }
  }

  @Test
  void malformedBodiesAndWrongMethodsAreRefused() {
    assertThat(postJson(PATH, "{not json").statusCode()).isEqualTo(400);
    assertThat(post(PATH, "text/plain", "username=agent").statusCode()).isEqualTo(415);
    assertThat(get(PATH).statusCode()).isEqualTo(405);
  }

  // --- audit and metrics -------------------------------------------------------------------

  @Test
  void successfulAndFailedSignInsAreAudited() {
    String name = "audited-" + UUID.randomUUID().toString().substring(0, 8);
    users.register(name, PASSWORD, Set.of(Role.AGENT), null);

    login(name, PASSWORD);
    login(name, "not-the-right-password");

    assertThat(auditCount(name, "TOKEN_ISSUED", "SUCCESS")).isEqualTo(1);
    assertThat(auditCount(name, "LOGIN_FAILED", "FAILURE")).isEqualTo(1);
    UUID userId = users.findByUsername(name).orElseThrow().id();
    assertThat(
            jdbc.sql(
                    "SELECT count(*) FROM identity.auth_audit "
                        + "WHERE username = :u AND user_id = :id AND outcome = 'SUCCESS'")
                .param("u", name)
                .param("id", userId)
                .query(Integer.class)
                .single())
        .isEqualTo(1);
  }

  @Test
  void aFailedSignInForAnUnknownUserIsAuditedWithoutAUserId() {
    String name = "ghost-" + UUID.randomUUID().toString().substring(0, 8);

    login(name, "not-the-right-password");

    assertThat(auditCount(name, "LOGIN_FAILED", "FAILURE")).isEqualTo(1);
    assertThat(
            jdbc.sql("SELECT user_id FROM identity.auth_audit WHERE username = :u")
                .param("u", name)
                .query(UUID.class)
                .optional())
        .isEmpty();
  }

  private int auditCount(String username, String type, String outcome) {
    return jdbc.sql(
            "SELECT count(*) FROM identity.auth_audit "
                + "WHERE username = :u AND event_type = :t AND outcome = :o")
        .param("u", username)
        .param("t", type)
        .param("o", outcome)
        .query(Integer.class)
        .single();
  }

  @Test
  void requestsAreCountedByOutcomeAndNeverByUser() {
    double successBefore = counted("success");
    double failureBefore = counted("failure");

    login("agent", PASSWORD);
    login("agent", "not-the-right-password");

    assertThat(counted("success")).isEqualTo(successBefore + 1);
    assertThat(counted("failure")).isEqualTo(failureBefore + 1);
    meters
        .find("identity.auth.token.requests")
        .counters()
        .forEach(
            counter ->
                assertThat(counter.getId().getTags())
                    .extracting(tag -> tag.getKey())
                    .containsExactlyInAnyOrder("outcome", "application"));
  }

  private double counted(String outcome) {
    var counter = meters.find("identity.auth.token.requests").tag("outcome", outcome).counter();
    return counter == null ? 0 : counter.count();
  }

  // --- no secrets in logs --------------------------------------------------------------------

  @Test
  void nothingSecretIsLoggedForSuccessFailureOrBrokenRequests(CapturedOutput output) {
    String wrong = "wrong-password-xyz-123";
    String token = tokenFor("agent");
    login("agent", wrong);
    login("nobody-here", wrong);
    postJson(PATH, "{\"username\":\"agent\",\"password\":\"" + wrong + "\"");
    String hash = users.findByUsername("agent").orElseThrow().passwordHash();

    assertThat(output.getAll())
        .doesNotContain(PASSWORD)
        .doesNotContain(wrong)
        .doesNotContain(token)
        .doesNotContain(token.substring(token.lastIndexOf('.') + 1))
        .doesNotContain(hash)
        .doesNotContain("Bearer ")
        .doesNotContain("access_token")
        // Spring Security's default in-memory user would print a generated password.
        .doesNotContainIgnoringCase("generated security password");
  }
}
