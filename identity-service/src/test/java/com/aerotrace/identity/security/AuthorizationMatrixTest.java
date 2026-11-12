package com.aerotrace.identity.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.aerotrace.identity.security.jwt.JwtTokenService;
import com.aerotrace.identity.support.IdentityTestBase;
import com.aerotrace.identity.support.TestKeys;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.json.JsonMapper;

/**
 * The authorization matrix: every caller type against every endpoint of the identity-service. 401
 * means "we do not know who you are" (no, invalid, expired, forged or wrongly addressed token), 403
 * means "we know you, and you may not". Anything not explicitly allowed is denied.
 */
@TestPropertySource(
    properties = {
      "aerotrace.identity.seed.enabled=true",
      "aerotrace.identity.seed.password=seed-test-password-1"
    })
class AuthorizationMatrixTest extends IdentityTestBase {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final String LOGIN =
      "{\"username\":\"agent\",\"password\":\"seed-test-password-1\"}";

  /** What an endpoint demands. */
  enum Access {
    PUBLIC,
    ANY_ROLE,
    NOT_EXPOSED
  }

  record Endpoint(String method, String path, String body, Access access) {
    @Override
    public String toString() {
      return method + " " + path;
    }
  }

  /** Who is calling. {@code valid} tokens are accepted as genuine; the rest must be a 401. */
  enum Caller {
    ANONYMOUS(false),
    PASSENGER(true),
    AGENT(true),
    DISPATCHER(true),
    ADMIN(true),
    NO_ROLES(true),
    UNKNOWN_ROLE(true),
    EXPIRED(false),
    WRONG_AUDIENCE(false),
    WRONG_ISSUER(false),
    FORGED_SIGNATURE(false),
    ALG_NONE(false),
    GARBAGE(false);

    final boolean valid;

    Caller(boolean valid) {
      this.valid = valid;
    }
  }

  static final List<Endpoint> ENDPOINTS =
      List.of(
          new Endpoint("POST", "/api/v1/auth/token", LOGIN, Access.PUBLIC),
          new Endpoint("GET", "/.well-known/jwks.json", null, Access.PUBLIC),
          new Endpoint("GET", "/actuator/health", null, Access.PUBLIC),
          new Endpoint("GET", "/api/v1/auth/me", null, Access.ANY_ROLE),
          new Endpoint("GET", "/api/v1/auth/policy", null, Access.ANY_ROLE),
          new Endpoint("GET", "/api/v1/auth/unknown", null, Access.NOT_EXPOSED),
          new Endpoint("POST", "/api/v1/auth/keys/rotate", null, Access.NOT_EXPOSED),
          new Endpoint("GET", "/actuator/env", null, Access.NOT_EXPOSED));

  static Stream<Arguments> matrix() {
    return ENDPOINTS.stream().flatMap(e -> Stream.of(Caller.values()).map(c -> Arguments.of(e, c)));
  }

  /** Only the combinations that are refused have an error format to check. */
  static Stream<Arguments> refusals() {
    return matrix()
        .filter(
            arguments -> {
              int status =
                  expectedStatus((Endpoint) arguments.get()[0], (Caller) arguments.get()[1]);
              return status == 401 || status == 403;
            });
  }

  static int expectedStatus(Endpoint endpoint, Caller caller) {
    return switch (endpoint.access()) {
      // Public means public: a stale or bad token sent along is ignored, not punished.
      case PUBLIC -> 200;
      case ANY_ROLE ->
          !caller.valid
              ? 401
              : switch (caller) {
                case PASSENGER, AGENT, DISPATCHER, ADMIN -> 200;
                default -> 403;
              };
      case NOT_EXPOSED -> caller.valid ? 403 : 401;
    };
  }

  @Autowired private JwtTokenService tokens;

  private String login(String username) {
    HttpResponse<String> response =
        postJson(
            "/api/v1/auth/token",
            "{\"username\":\"" + username + "\",\"password\":\"seed-test-password-1\"}");
    return JSON.readTree(response.body()).get("data").get("accessToken").asString();
  }

  /** A token signed with the real private key, with exactly the claims a test wants. */
  private String signed(String issuer, String audience, List<String> roles, Instant expires) {
    return TestKeys.token(
        TestKeys.bundledPrivateKey(), tokens.keyId(), issuer, audience, roles, expires);
  }

  private static Instant soon() {
    return Instant.now().plus(Duration.ofMinutes(10));
  }

  private String tokenFor(Caller caller) throws Exception {
    return switch (caller) {
      case ANONYMOUS -> null;
      case PASSENGER -> login("passenger");
      case AGENT -> login("agent");
      case DISPATCHER -> login("dispatcher");
      case ADMIN -> login("admin");
      case NO_ROLES -> signed(TestKeys.ISSUER, TestKeys.AUDIENCE, List.of(), soon());
      case UNKNOWN_ROLE -> signed(TestKeys.ISSUER, TestKeys.AUDIENCE, List.of("ROOT"), soon());
      case EXPIRED ->
          signed(
              TestKeys.ISSUER,
              TestKeys.AUDIENCE,
              List.of("ADMIN"),
              Instant.now().minus(Duration.ofHours(1)));
      case WRONG_AUDIENCE -> signed(TestKeys.ISSUER, "another-api", List.of("ADMIN"), soon());
      case WRONG_ISSUER ->
          signed("http://someone-else", TestKeys.AUDIENCE, List.of("ADMIN"), soon());
      case FORGED_SIGNATURE -> {
        // An admin token signed by a key the service never published, claiming the real key id.
        RSAKey attacker = new RSAKeyGenerator(2048).generate();
        SignedJWT forged =
            new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(tokens.keyId()).build(),
                adminClaims());
        forged.sign(new RSASSASigner(attacker));
        yield forged.serialize();
      }
      case ALG_NONE -> new PlainJWT(adminClaims()).serialize();
      case GARBAGE -> "this-is-not-a-jwt";
    };
  }

  private static JWTClaimsSet adminClaims() {
    return new JWTClaimsSet.Builder()
        .issuer(TestKeys.ISSUER)
        .audience(TestKeys.AUDIENCE)
        .subject(UUID.randomUUID().toString())
        .issueTime(new Date())
        .expirationTime(Date.from(soon()))
        .claim("roles", List.of("ADMIN"))
        .build();
  }

  @ParameterizedTest(name = "{0} as {1}")
  @MethodSource("matrix")
  void everyCallerGetsTheExpectedAnswerFromEveryEndpoint(Endpoint endpoint, Caller caller)
      throws Exception {
    String token = tokenFor(caller);

    HttpResponse<String> response =
        send(endpoint.method(), endpoint.path(), endpoint.body(), token);

    assertThat(response.statusCode()).isEqualTo(expectedStatus(endpoint, caller));
  }

  @ParameterizedTest(name = "{0} as {1}")
  @MethodSource("refusals")
  void refusalsUseTheSharedErrorFormatAndRevealNothing(Endpoint endpoint, Caller caller)
      throws Exception {
    int expected = expectedStatus(endpoint, caller);

    HttpResponse<String> response =
        send(endpoint.method(), endpoint.path(), endpoint.body(), tokenFor(caller));

    var problem = JSON.readTree(response.body());
    assertThat(problem.get("code").asString())
        .isEqualTo(expected == 401 ? "UNAUTHENTICATED" : "ACCESS_DENIED");
    assertThat(problem.get("status").asInt()).isEqualTo(expected);
    assertThat(response.headers().firstValue("Content-Type").orElse(""))
        .contains("application/json");
    // No hint about why a token was refused (expired? wrong audience? bad signature?).
    assertThat(response.body())
        .doesNotContainIgnoringCase("expired")
        .doesNotContainIgnoringCase("signature")
        .doesNotContainIgnoringCase("audience")
        .doesNotContainIgnoringCase("issuer")
        .doesNotContainIgnoringCase("jwt");
    if (expected == 401) {
      assertThat(response.headers().firstValue("WWW-Authenticate").orElse("")).startsWith("Bearer");
    }
  }
}
