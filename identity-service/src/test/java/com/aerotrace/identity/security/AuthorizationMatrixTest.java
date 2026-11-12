package com.aerotrace.identity.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.aerotrace.identity.base.AbstractIntegrationTest;
import com.aerotrace.identity.security.jwt.JwtTokenService;
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
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.json.JsonMapper;

/**
 * The authorization matrix: every caller type against every endpoint of the identity-service. 401
 * means "we do not know who you are" (no, invalid, expired, forged or wrongly addressed token), 403
 * means "we know you, and you may not". Anything not explicitly allowed is denied.
 */
class AuthorizationMatrixTest extends AbstractIntegrationTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  /** What an endpoint demands. */
  enum Access {
    /** Open to everyone; a stale or bad token sent along is ignored, not punished. */
    PUBLIC,
    /** Registers a passenger: no token needed, but a token that is sent must be genuine. */
    REGISTER_PASSENGER,
    /** Registers a staff account: only an ADMIN. */
    REGISTER_STAFF,
    /** A path that exists, called with a method it does not have. */
    WRONG_METHOD,
    /** Not an endpoint of this service at all. */
    NOT_EXPOSED
  }

  record Endpoint(String method, String path, Supplier<String> body, Access access) {
    @Override
    public String toString() {
      return method + " " + path + " (" + access + ")";
    }
  }

  /** Who is calling. {@code valid} tokens are genuine; {@code anonymous} sends none at all. */
  enum Caller {
    ANONYMOUS(true, false),
    PASSENGER(false, true),
    AGENT(false, true),
    DISPATCHER(false, true),
    ADMIN(false, true),
    NO_ROLES(false, true),
    UNKNOWN_ROLE(false, true),
    EXPIRED(false, false),
    WRONG_AUDIENCE(false, false),
    WRONG_ISSUER(false, false),
    FORGED_SIGNATURE(false, false),
    ALG_NONE(false, false),
    GARBAGE(false, false);

    final boolean anonymous;
    final boolean valid;

    Caller(boolean anonymous, boolean valid) {
      this.anonymous = anonymous;
      this.valid = valid;
    }
  }

  private static String registration(String role) {
    String name = "m-" + unique();
    return body(
        "username", name,
        "email", name + "@example.com",
        "password", PASSWORD,
        "fullName", "Matrix User",
        "role", role);
  }

  static final List<Endpoint> ENDPOINTS =
      List.of(
          new Endpoint(
              "POST",
              "/api/v1/auth/register",
              () -> registration("PASSENGER"),
              Access.REGISTER_PASSENGER),
          new Endpoint(
              "POST", "/api/v1/auth/register", () -> registration("AGENT"), Access.REGISTER_STAFF),
          new Endpoint(
              "POST",
              "/api/v1/auth/logout",
              () -> body("refreshToken", "not-a-real-token"),
              Access.PUBLIC),
          new Endpoint(
              "POST",
              "/api/v1/auth/forgot-password",
              () -> body("usernameOrEmail", "nobody-" + unique()),
              Access.PUBLIC),
          new Endpoint("GET", "/.well-known/jwks.json", () -> null, Access.PUBLIC),
          new Endpoint("GET", "/actuator/health", () -> null, Access.PUBLIC),
          new Endpoint("GET", "/api/v1/auth/login", () -> null, Access.WRONG_METHOD),
          new Endpoint("GET", "/api/v1/auth/me", () -> null, Access.NOT_EXPOSED),
          new Endpoint("GET", "/api/v1/auth/policy", () -> null, Access.NOT_EXPOSED),
          new Endpoint("POST", "/api/v1/auth/token", () -> "{}", Access.NOT_EXPOSED),
          new Endpoint("POST", "/api/v1/auth/keys/rotate", () -> null, Access.NOT_EXPOSED),
          new Endpoint("GET", "/actuator/env", () -> null, Access.NOT_EXPOSED));

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
      case PUBLIC -> 200;
      case WRONG_METHOD -> 405;
      case REGISTER_PASSENGER -> caller.anonymous || caller.valid ? 201 : 401;
      case REGISTER_STAFF -> caller == Caller.ADMIN ? 201 : caller.valid ? 403 : 401;
      case NOT_EXPOSED -> caller.valid ? 403 : 401;
    };
  }

  @Autowired private JwtTokenService tokens;

  /** A token signed with the real private key, with exactly the claims a test wants. */
  private String signed(String issuer, String audience, List<String> roles, Instant expires) {
    return TestKeys.token(
        TestKeys.bundledPrivateKey(), tokens.keyId(), issuer, audience, roles, expires);
  }

  private static Instant soon() {
    return Instant.now().plus(Duration.ofMinutes(10));
  }

  private String valid(List<String> roles) {
    return signed(TestKeys.ISSUER, TestKeys.AUDIENCE, roles, soon());
  }

  private String tokenFor(Caller caller) throws Exception {
    return switch (caller) {
      case ANONYMOUS -> null;
      case PASSENGER -> valid(List.of("PASSENGER"));
      case AGENT -> valid(List.of("AGENT"));
      case DISPATCHER -> valid(List.of("DISPATCHER"));
      case ADMIN -> valid(List.of("ADMIN"));
      case NO_ROLES -> valid(List.of());
      case UNKNOWN_ROLE -> valid(List.of("ROOT"));
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
    HttpResponse<String> response =
        send(endpoint.method(), endpoint.path(), endpoint.body().get(), tokenFor(caller));

    assertThat(response.statusCode()).isEqualTo(expectedStatus(endpoint, caller));
  }

  @ParameterizedTest(name = "{0} as {1}")
  @MethodSource("refusals")
  void refusalsUseTheSharedErrorFormatAndRevealNothing(Endpoint endpoint, Caller caller)
      throws Exception {
    int expected = expectedStatus(endpoint, caller);

    HttpResponse<String> response =
        send(endpoint.method(), endpoint.path(), endpoint.body().get(), tokenFor(caller));

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
