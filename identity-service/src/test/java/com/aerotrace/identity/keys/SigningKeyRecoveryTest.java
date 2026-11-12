package com.aerotrace.identity.keys;

import static org.assertj.core.api.Assertions.assertThat;

import com.aerotrace.identity.model.entity.SigningKeyEntity;
import com.aerotrace.identity.repository.SigningKeyRepository;
import com.aerotrace.identity.support.ClockTestConfiguration;
import com.aerotrace.identity.support.IdentityTestBase;
import com.aerotrace.identity.support.MutableClock;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.security.KeyPair;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

/**
 * A signing key that cannot be decrypted (the key-encryption key was changed, or a row is damaged)
 * must not take token issuing, verification and the published key set down with it.
 */
@Import(ClockTestConfiguration.class)
@TestPropertySource(properties = "aerotrace.identity.keys.cache-ttl=0s")
class SigningKeyRecoveryTest extends IdentityTestBase {

  private static final Instant START = Instant.parse("2030-01-01T00:00:00Z");
  private static final String OTHER_KEK = "H4SJWYYGmkU3gfNFSiLyx2ETcm3GWvtbH8KLzF6vYRk=";
  private static final String BROKEN = "broken-kid";

  @Autowired private SigningKeyService keys;
  @Autowired private SigningKeyRepository repository;
  @Autowired private JwtSigner signer;
  @Autowired private MutableClock clock;

  @BeforeEach
  void aKeyEncryptedWithAnotherKeyIsTheOnlyOne() {
    repository.deleteAll();
    clock.set(START);
    KeyPair pair = SigningAlgorithm.RS256.generate();
    repository.save(
        new SigningKeyEntity(
            BROKEN,
            "RS256",
            Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()),
            KeyCipher.fromBase64(OTHER_KEK).encrypt(pair.getPrivate().getEncoded(), BROKEN),
            START.minus(Duration.ofHours(1)),
            START.minus(Duration.ofHours(1))));
  }

  @AfterEach
  void cleanUp() {
    repository.deleteAll();
  }

  @Test
  void theKeySetIsStillServedWithoutNeedingTheUnreadableKey() {
    assertThat(keys.publishedKeys().getKeyByKeyId(BROKEN)).isNotNull();

    // Reading the public keys changed nothing.
    assertThat(repository.count()).isEqualTo(1);
  }

  @Test
  void signingMovesToAFreshKeyAndTheOldOneStaysPublishedForItsTokens() throws Exception {
    String token =
        signer.sign(
            new JWTClaimsSet.Builder()
                .subject("alice")
                .expirationTime(Date.from(clock.instant().plusSeconds(900)))
                .build());

    SignedJWT jwt = SignedJWT.parse(token);
    String kid = jwt.getHeader().getKeyID();
    assertThat(kid).isNotEqualTo(BROKEN);
    assertThat(repository.count()).isEqualTo(2);
    assertThat(keys.publishedKeys().getKeyByKeyId(BROKEN)).isNotNull();
    assertThat(jwt.verify(new RSASSAVerifier((RSAKey) keys.publishedKeys().getKeyByKeyId(kid))))
        .isTrue();
  }

  @Test
  void recoveringHappensOnceNotOnEveryRequest() {
    keys.signingKey();
    keys.signingKey();
    keys.signingKey();

    assertThat(repository.count()).isEqualTo(2);
  }
}
