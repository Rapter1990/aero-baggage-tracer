package com.aerotrace.identity.security.jwt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aerotrace.identity.support.MutableClock;
import com.aerotrace.identity.support.TestKeys;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.security.KeyPair;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/** Signing and verifying access tokens with the PEM key pair. */
@ExtendWith(OutputCaptureExtension.class)
class JwtTokenServiceTest {

  private static final Instant NOW = Instant.parse("2030-01-01T00:00:00Z");

  private final MutableClock clock = new MutableClock(NOW);

  private JwtProperties bundled() {
    return properties(TestKeys.AUDIENCE, Duration.ofMinutes(15));
  }

  private static JwtProperties properties(String audience, Duration ttl) {
    return new JwtProperties(audience, ttl, Duration.ofMinutes(1), null, null, null, null);
  }

  private JwtTokenService service() {
    return new JwtTokenService(bundled(), clock);
  }

  // --- what a token carries ----------------------------------------------------------------

  @Test
  void aTokenCarriesTheAgreedClaims() {
    JwtTokenService service = service();

    Claims claims =
        service.parseClaims(service.generateToken("user-1", List.of("AGENT", "DISPATCHER"), null));

    assertThat(claims.getIssuer()).isEqualTo(TestKeys.ISSUER);
    assertThat(claims.getAudience()).containsExactly(TestKeys.AUDIENCE);
    assertThat(claims.getSubject()).isEqualTo("user-1");
    assertThat(claims.get("roles", List.class)).containsExactly("AGENT", "DISPATCHER");
    assertThat(claims.getId()).isNotBlank();
    assertThat(claims.getIssuedAt().toInstant()).isEqualTo(NOW);
    assertThat(claims.getExpiration().toInstant()).isEqualTo(NOW.plus(Duration.ofMinutes(15)));
  }

  @Test
  void aPassengerTokenCarriesThePassengerReferenceAndStaffTokensDoNot() {
    JwtTokenService service = service();

    Claims passenger =
        service.parseClaims(
            service.generateToken("p-1", List.of("PASSENGER"), "P-00000000000000000001"));
    Claims staff = service.parseClaims(service.generateToken("s-1", List.of("AGENT"), null));

    assertThat(passenger.get("passengerRef", String.class)).isEqualTo("P-00000000000000000001");
    assertThat(staff).doesNotContainKey("passengerRef");
  }

  @Test
  void theHeaderStatesTheAlgorithmAndType() throws Exception {
    JwtTokenService service = service();

    SignedJWT jwt = SignedJWT.parse(service.generateToken("u", List.of("AGENT"), null));

    assertThat(jwt.getHeader().getAlgorithm().getName()).isEqualTo("RS256");
    assertThat(jwt.getHeader().getType().getType()).isEqualTo("JWT");
  }

  @Test
  void everyTokenHasItsOwnId() {
    JwtTokenService service = service();

    String first = service.parseClaims(service.generateToken("u", List.of("AGENT"), null)).getId();
    String second = service.parseClaims(service.generateToken("u", List.of("AGENT"), null)).getId();

    assertThat(first).isNotEqualTo(second);
  }

  // --- what it refuses ---------------------------------------------------------------------

  @Test
  void aTamperedTokenIsRefused() {
    JwtTokenService service = service();
    String token = service.generateToken("alice", List.of("PASSENGER"), "P-00000000000000000001");
    String[] parts = token.split("\\.");
    String payload =
        new String(java.util.Base64.getUrlDecoder().decode(parts[1])).replace("PASSENGER", "ADMIN");
    String forged =
        parts[0]
            + "."
            + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes())
            + "."
            + parts[2];

    assertThatThrownBy(() -> service.parseClaims(forged)).isInstanceOf(JwtException.class);
  }

  @Test
  void aTokenSignedByAnotherKeyIsRefused() {
    JwtTokenService service = service();
    KeyPair attacker = TestKeys.generate();
    String forged =
        TestKeys.token(
            attacker.getPrivate(),
            null,
            TestKeys.ISSUER,
            TestKeys.AUDIENCE,
            List.of("ADMIN"),
            NOW.plus(Duration.ofMinutes(5)));

    assertThatThrownBy(() -> service.parseClaims(forged)).isInstanceOf(JwtException.class);
  }

  @Test
  void aTokenForAnotherIssuerOrAudienceIsRefused() {
    JwtTokenService service = service();

    // Signed with the right key, but by someone who calls themselves another issuer.
    String foreign =
        TestKeys.token(
            TestKeys.bundledPrivateKey(),
            null,
            "http://someone-else",
            TestKeys.AUDIENCE,
            List.of("AGENT"),
            NOW.plus(Duration.ofMinutes(5)));

    assertThatThrownBy(() -> service.parseClaims(foreign)).isInstanceOf(JwtException.class);
    String wrongAudience =
        new JwtTokenService(properties("another-api", Duration.ofMinutes(15)), clock)
            .generateToken("u", List.of("AGENT"), null);
    assertThatThrownBy(() -> service.parseClaims(wrongAudience)).isInstanceOf(JwtException.class);
  }

  @Test
  void anExpiredTokenIsRefusedOnceTheClockSkewIsUsedUp() {
    JwtTokenService service = service();
    String token = service.generateToken("u", List.of("AGENT"), null);

    clock.advance(Duration.ofMinutes(15).plusSeconds(30));
    assertThat(service.parseClaims(token).getSubject()).isEqualTo("u");

    clock.advance(Duration.ofMinutes(1));
    assertThatThrownBy(() -> service.parseClaims(token)).isInstanceOf(JwtException.class);
  }

  @Test
  void anUnsignedTokenIsRefused() {
    String unsigned =
        new PlainJWT(
                new JWTClaimsSet.Builder()
                    .issuer(TestKeys.ISSUER)
                    .audience(TestKeys.AUDIENCE)
                    .subject("admin")
                    .expirationTime(Date.from(NOW.plus(Duration.ofMinutes(5))))
                    .build())
            .serialize();

    assertThatThrownBy(() -> service().parseClaims(unsigned)).isInstanceOf(JwtException.class);
  }

  @Test
  void aTokenSignedWithTheSharedSecretTrickIsRefused() {
    // Algorithm confusion: HS256 keyed with the public key's bytes, which everyone has.
    String forged =
        Jwts.builder()
            .issuer(TestKeys.ISSUER)
            .audience()
            .add(TestKeys.AUDIENCE)
            .and()
            .subject("admin")
            .expiration(Date.from(NOW.plus(Duration.ofMinutes(5))))
            .signWith(Keys.hmacShaKeyFor(TestKeys.bundledPublicKey().getEncoded()))
            .compact();

    assertThatThrownBy(() -> service().parseClaims(forged)).isInstanceOf(JwtException.class);
  }

  @Test
  void aTokenWithoutAnExpiryIsRefused() {
    String forever =
        TestKeys.token(
            TestKeys.bundledPrivateKey(),
            null,
            TestKeys.ISSUER,
            TestKeys.AUDIENCE,
            List.of("ADMIN"),
            null);

    assertThatThrownBy(() -> service().parseClaims(forever)).isInstanceOf(JwtException.class);
  }

  // --- the key pair ------------------------------------------------------------------------

  @Test
  void aPrivateAndPublicKeyThatAreNotAPairStopTheStart() {
    KeyPair other = TestKeys.generate();
    JwtProperties mismatched =
        new JwtProperties(
            TestKeys.AUDIENCE,
            Duration.ofMinutes(15),
            Duration.ofMinutes(1),
            null,
            null,
            TestKeys.publicPem(other.getPublic()),
            null);

    assertThatThrownBy(() -> new JwtTokenService(mismatched, clock))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("do not match");
  }

  @Test
  void aTokenThatNeverExpiresCannotBeConfigured() {
    assertThatThrownBy(
            () -> new JwtTokenService(properties(TestKeys.AUDIENCE, Duration.ZERO), clock))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("ttl");
  }

  @Test
  void yourOwnPairWorksAndSilencesTheWarning(CapturedOutput output) {
    KeyPair own = TestKeys.generate();
    JwtProperties custom =
        new JwtProperties(
            TestKeys.AUDIENCE,
            Duration.ofMinutes(15),
            Duration.ofMinutes(1),
            TestKeys.privatePem(own.getPrivate()),
            null,
            TestKeys.publicPem(own.getPublic()),
            null);

    JwtTokenService service = new JwtTokenService(custom, clock);

    assertThat(custom.usesBundledKeys()).isFalse();
    assertThat(service.parseClaims(service.generateToken("u", List.of("AGENT"), null)).getSubject())
        .isEqualTo("u");
    assertThat(output.getAll()).doesNotContain("development key pair");
  }

  @Test
  void theBundledPairIsUsableButLoudlyFlagged(CapturedOutput output) {
    JwtTokenService service = service();

    assertThat(bundled().usesBundledKeys()).isTrue();
    assertThat(service.generateToken("u", List.of("AGENT"), null)).isNotBlank();
    assertThat(output.getAll()).contains("development key pair").contains("anyone can sign tokens");
  }

  @Test
  void thePropertiesNeverPrintKeyMaterial() {
    KeyPair own = TestKeys.generate();
    String pem = TestKeys.privatePem(own.getPrivate());
    JwtProperties custom =
        new JwtProperties(
            TestKeys.AUDIENCE,
            Duration.ofMinutes(15),
            Duration.ofMinutes(1),
            pem,
            null,
            null,
            null);

    assertThat(custom.toString())
        .doesNotContain("PRIVATE KEY")
        .doesNotContain(pem.substring(40, 80));
  }
}
