package com.aerotrace.identity.keys;

import static org.assertj.core.api.Assertions.assertThat;

import com.aerotrace.identity.support.ClockTestConfiguration;
import com.aerotrace.identity.support.IdentityTestBase;
import com.aerotrace.identity.support.MutableClock;
import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.text.ParseException;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;

/**
 * Key lifecycle against a real database and a controllable clock. With a 10 minute publish lead, a
 * 15 minute token lifetime and 1 minute skew, a replaced key stays published until 26 minutes after
 * the rotation. Subclasses pick the algorithm.
 */
@Import(ClockTestConfiguration.class)
@TestPropertySource(
    properties = {
      "aerotrace.identity.keys.cache-ttl=0s",
      "aerotrace.identity.keys.publish-lead=10m",
      "aerotrace.identity.keys.rotation-interval=30d",
      "aerotrace.identity.token.ttl=15m",
      "aerotrace.identity.token.clock-skew=1m"
    })
abstract class AbstractSigningKeyTest extends IdentityTestBase {

  @Autowired SigningKeyService keys;
  @Autowired JwtSigner signer;
  @Autowired MutableClock clock;
  @Autowired JdbcClient jdbc;

  abstract String algorithm();

  abstract String keyType();

  @BeforeEach
  void freshKeys() {
    jdbc.sql("DELETE FROM identity.signing_key").update();
    clock.set(Instant.parse("2030-01-01T00:00:00Z"));
    keys.ensureKeyExists();
  }

  @AfterEach
  void cleanUp() {
    // Keys made under the fake clock must not leak into other tests that use the real time.
    jdbc.sql("DELETE FROM identity.signing_key").update();
  }

  private JWTClaimsSet claims() {
    return new JWTClaimsSet.Builder()
        .subject("alice")
        .issuer("test")
        .expirationTime(Date.from(clock.instant().plus(Duration.ofMinutes(15))))
        .build();
  }

  private List<String> publishedKids() {
    return keys.publishedKeys().getKeys().stream().map(JWK::getKeyID).toList();
  }

  private String kidOf(String token) throws ParseException {
    return SignedJWT.parse(token).getHeader().getKeyID();
  }

  private boolean verifies(String token) throws Exception {
    SignedJWT jwt = SignedJWT.parse(token);
    JWK jwk = keys.publishedKeys().getKeyByKeyId(jwt.getHeader().getKeyID());
    if (jwk == null) {
      return false;
    }
    JWSVerifier verifier =
        jwk instanceof RSAKey rsa ? new RSASSAVerifier(rsa) : new ECDSAVerifier((ECKey) jwk);
    return jwt.verify(verifier);
  }

  private int keyRows() {
    return jdbc.sql("SELECT count(*) FROM identity.signing_key").query(Integer.class).single();
  }

  @Test
  void bootstrapPublishesOneKeyOfTheConfiguredType() {
    JWKSet set = keys.publishedKeys();

    assertThat(set.getKeys()).hasSize(1);
    JWK jwk = set.getKeys().get(0);
    assertThat(jwk.getKeyType().getValue()).isEqualTo(keyType());
    assertThat(jwk.getAlgorithm().getName()).isEqualTo(algorithm());
    assertThat(jwk.getKeyUse()).isEqualTo(KeyUse.SIGNATURE);
    assertThat(jwk.getKeyID()).isNotBlank();
    assertThat(keys.signingKey().kid()).isEqualTo(jwk.getKeyID());
  }

  @Test
  void thePublishedKeysContainNoPrivateMaterial() {
    assertThat(keys.publishedKeys().getKeys()).noneMatch(JWK::isPrivate);
    assertThat(keys.publishedKeys().toString()).doesNotContain("\"d\"", "\"p\"", "\"q\"");
  }

  @Test
  void ensuringAKeyExistsTwiceStillLeavesOneKey() {
    keys.ensureKeyExists();
    keys.ensureKeyExists();

    assertThat(keyRows()).isEqualTo(1);
  }

  @Test
  void aSignedTokenCarriesTheKeyIdAndVerifiesAgainstThePublishedKey() throws Exception {
    String token = signer.sign(claims());

    SignedJWT jwt = SignedJWT.parse(token);
    assertThat(jwt.getHeader().getAlgorithm().getName()).isEqualTo(algorithm());
    assertThat(jwt.getHeader().getType().getType()).isEqualTo("JWT");
    assertThat(jwt.getHeader().getKeyID()).isEqualTo(keys.signingKey().kid());
    assertThat(verifies(token)).isTrue();
  }

  @Test
  void aTamperedTokenDoesNotVerify() throws Exception {
    String token = signer.sign(claims());
    String[] parts = token.split("\\.");
    String payload = new String(Base64.getUrlDecoder().decode(parts[1])).replace("alice", "admin");
    String forged =
        parts[0]
            + "."
            + Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes())
            + "."
            + parts[2];

    assertThat(verifies(forged)).isFalse();
  }

  @Test
  void aNewKeyIsPublishedBeforeItStartsSigning() throws Exception {
    String first = keys.signingKey().kid();

    String second = keys.rotate();

    assertThat(publishedKids()).containsExactlyInAnyOrder(first, second);
    assertThat(kidOf(signer.sign(claims()))).isEqualTo(first);

    clock.advance(Duration.ofMinutes(10));

    assertThat(kidOf(signer.sign(claims()))).isEqualTo(second);
  }

  @Test
  void rotationDoesNotInvalidateTokensThatHaveNotExpired() throws Exception {
    String oldToken = signer.sign(claims());

    String second = keys.rotate();
    clock.advance(Duration.ofMinutes(11));
    keys.retireExpired();

    assertThat(verifies(oldToken)).isTrue();
    String newToken = signer.sign(claims());
    assertThat(kidOf(newToken)).isEqualTo(second);
    assertThat(verifies(newToken)).isTrue();
  }

  @Test
  void theOldKeyStaysPublishedUntilItsLastTokenCouldHaveExpired() throws Exception {
    String first = keys.signingKey().kid();
    String second = keys.rotate();

    // Activation (10m) + token lifetime (15m): a token signed at the very end is just expiring.
    clock.advance(Duration.ofMinutes(25));
    keys.retireExpired();
    assertThat(publishedKids()).containsExactlyInAnyOrder(first, second);

    // Past the skew allowance it is no longer needed.
    clock.advance(Duration.ofMinutes(1).plusSeconds(1));
    keys.retireExpired();
    assertThat(publishedKids()).containsExactly(second);
  }

  @Test
  void aRetiredKeyLosesItsPrivateKey() {
    String first = keys.signingKey().kid();
    String second = keys.rotate();
    clock.advance(Duration.ofMinutes(27));

    keys.retireExpired();

    assertThat(privateKeyOf(first)).isNull();
    assertThat(privateKeyOf(second)).isNotNull();
  }

  private String privateKeyOf(String kid) {
    return jdbc.sql("SELECT private_key_enc FROM identity.signing_key WHERE kid = :kid")
        .param("kid", kid)
        .query(String.class)
        .optional()
        .orElse(null);
  }

  @Test
  void rotationIsDueOnlyOnceTheIntervalHasPassed() {
    assertThat(keys.rotateIfDue()).isEmpty();

    clock.advance(Duration.ofDays(29));
    assertThat(keys.rotateIfDue()).isEmpty();

    clock.advance(Duration.ofDays(1).plusSeconds(1));
    Optional<String> rotated = keys.rotateIfDue();
    assertThat(rotated).isPresent();
    assertThat(publishedKids()).contains(rotated.get());

    assertThat(keys.rotateIfDue()).isEmpty();
  }

  @Test
  void rotatingTwiceBeforeActivationDoesNotStackKeys() {
    String second = keys.rotate();
    String again = keys.rotate();

    assertThat(again).isEqualTo(second);
    assertThat(keyRows()).isEqualTo(2);
  }

  @Test
  void replicasRotatingAtTheSameTimeCreateOnlyOneKey() throws Exception {
    clock.advance(Duration.ofDays(31));
    ExecutorService pool = Executors.newFixedThreadPool(4);
    try {
      List<Callable<Optional<String>>> tasks =
          List.of(keys::rotateIfDue, keys::rotateIfDue, keys::rotateIfDue, keys::rotateIfDue);
      long created = 0;
      for (Future<Optional<String>> result : pool.invokeAll(tasks)) {
        created += result.get().isPresent() ? 1 : 0;
      }
      assertThat(created).isEqualTo(1);
      assertThat(keyRows()).isEqualTo(2);
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void privateKeysAreEncryptedAtRest() {
    String kid = keys.signingKey().kid();
    String pkcs8 = Base64.getEncoder().encodeToString(keys.signingKey().privateKey().getEncoded());

    String stored = privateKeyOf(kid);

    assertThat(stored).isNotEqualTo(pkcs8).doesNotContain(pkcs8.substring(20, 60));
  }

  @Test
  void rotationIsRecordedInTheAuditTrail() {
    int before = auditedRotations();

    keys.rotate();

    assertThat(auditedRotations()).isEqualTo(before + 1);
  }

  private int auditedRotations() {
    return jdbc.sql(
            "SELECT count(*) FROM identity.auth_audit "
                + "WHERE event_type = 'KEY_ROTATED' AND outcome = 'SUCCESS'")
        .query(Integer.class)
        .single();
  }

  @Test
  void aWipedKeyTableHealsItselfOnTheNextSigning() throws Exception {
    jdbc.sql("DELETE FROM identity.signing_key").update();

    String token = signer.sign(claims());

    assertThat(verifies(token)).isTrue();
    assertThat(keyRows()).isEqualTo(1);
  }

  @Test
  void theSigningKeyNeverPrintsItsPrivateKey() {
    SigningKey key = keys.signingKey();
    String pkcs8 = Base64.getEncoder().encodeToString(key.privateKey().getEncoded());

    assertThat(key.toString()).doesNotContain(pkcs8.substring(20, 60)).contains(key.kid());
  }
}
