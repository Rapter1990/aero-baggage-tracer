package com.aerotrace.identity.keys;

import com.aerotrace.identity.audit.AuthAuditLog;
import com.aerotrace.identity.audit.AuthEventType;
import com.aerotrace.identity.audit.AuthOutcome;
import com.aerotrace.identity.keys.SigningKeyRepository.StoredKey;
import com.aerotrace.identity.token.TokenProperties;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import java.security.PublicKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Generates, rotates and retires signing keys, and answers which key signs now and which keys are
 * published.
 *
 * <p>The newest activated key signs. A rotation publishes the new key at once but lets it start
 * signing only after {@code publishLead}, so verifiers have the key before the first token carries
 * its id. The key it replaces is retired once its last token can no longer be valid. Changes are
 * serialized across replicas by a database advisory lock, so replicas never create competing keys.
 */
@Service
public class SigningKeyService {

  private static final Logger log = LoggerFactory.getLogger(SigningKeyService.class);

  private record Snapshot(Instant loadedAt, SigningKey signing, JWKSet published) {}

  private record Rotation(String kid, boolean created) {}

  private final SigningKeyRepository repository;
  private final KeyCipher cipher;
  private final KeyProperties properties;
  private final TokenProperties tokens;
  private final Clock clock;
  private final AuthAuditLog audit;
  private final TransactionTemplate transaction;
  private final SigningAlgorithm algorithm;

  private volatile Snapshot snapshot;

  SigningKeyService(
      SigningKeyRepository repository,
      KeyCipher cipher,
      KeyProperties properties,
      TokenProperties tokens,
      Clock clock,
      AuthAuditLog audit,
      TransactionTemplate transaction) {
    this.repository = repository;
    this.cipher = cipher;
    this.properties = properties;
    this.tokens = tokens;
    this.clock = clock;
    this.audit = audit;
    this.transaction = transaction;
    this.algorithm = SigningAlgorithm.parse(properties.algorithm());
  }

  /** Creates the first key if there is none. Safe to call from every replica at start-up. */
  public void ensureKeyExists() {
    transaction.executeWithoutResult(
        status -> {
          repository.lock();
          Instant now = clock.instant();
          if (repository.countPublished(now) == 0) {
            String kid = create(now, now);
            log.info("Created the first signing key {}", kid);
          }
        });
    snapshot = null;
  }

  /** The key to sign with now. */
  public SigningKey signingKey() {
    return current().signing();
  }

  /** The public keys to publish (current, upcoming and not-yet-retired previous keys). */
  public JWKSet publishedKeys() {
    return current().published();
  }

  /**
   * Introduces a new key. If one is already waiting to start signing, that one is returned instead
   * of stacking another.
   */
  public String rotate() {
    Rotation result =
        transaction.execute(
            status -> {
              repository.lock();
              return rotateLocked(clock.instant());
            });
    return afterRotation(result);
  }

  /** Rotates when the newest key has been signing for the rotation interval. */
  public Optional<String> rotateIfDue() {
    Rotation result =
        transaction.execute(
            status -> {
              repository.lock();
              Instant now = clock.instant();
              Optional<Instant> newest = repository.newestActivation();
              if (newest.isEmpty()) {
                return new Rotation(create(now, now), true);
              }
              boolean waiting = repository.findPending(now).isPresent();
              if (waiting || now.isBefore(newest.get().plus(properties.rotationInterval()))) {
                return new Rotation(null, false);
              }
              return rotateLocked(now);
            });
    if (result.kid() == null || !result.created()) {
      return Optional.empty();
    }
    return Optional.of(afterRotation(result));
  }

  /** Unpublishes keys whose last token has expired and erases their private keys. */
  public void retireExpired() {
    int erased = repository.eraseRetiredPrivateKeys(clock.instant());
    if (erased > 0) {
      log.info("Retired {} signing key(s) and erased their private keys", erased);
    }
    snapshot = null;
  }

  private Rotation rotateLocked(Instant now) {
    Optional<StoredKey> waiting = repository.findPending(now);
    if (waiting.isPresent()) {
      return new Rotation(waiting.get().kid(), false);
    }
    if (repository.countPublished(now) == 0) {
      return new Rotation(create(now, now), true);
    }
    Instant activateAt = now.plus(properties.publishLead());
    String kid = create(now, activateAt);
    Duration lifetime = tokens.ttl().plus(tokens.clockSkew());
    repository.scheduleRetirement(activateAt.plus(lifetime), kid);
    return new Rotation(kid, true);
  }

  private String afterRotation(Rotation rotation) {
    snapshot = null;
    if (rotation.created()) {
      audit.record(AuthEventType.KEY_ROTATED, AuthOutcome.SUCCESS, null, null);
      log.info("Introduced signing key {}", rotation.kid());
    }
    return rotation.kid();
  }

  private String create(Instant createdAt, Instant activateAt) {
    var pair = algorithm.generate();
    PublicKey publicKey = pair.getPublic();
    String kid = thumbprint(publicKey);
    repository.insert(
        new StoredKey(
            kid,
            algorithm.name(),
            Base64.getEncoder().encodeToString(publicKey.getEncoded()),
            cipher.encrypt(pair.getPrivate().getEncoded(), kid),
            createdAt,
            activateAt,
            null));
    return kid;
  }

  /** The RFC 7638 JWK thumbprint, so a key id is derived from the key and never reused. */
  private String thumbprint(PublicKey publicKey) {
    try {
      return algorithm.toJwk(publicKey, null).computeThumbprint().toString();
    } catch (com.nimbusds.jose.JOSEException e) {
      return UUID.randomUUID().toString();
    }
  }

  private Snapshot current() {
    Snapshot cached = snapshot;
    Instant now = clock.instant();
    Duration ttl = properties.cacheTtl();
    if (cached != null
        && !ttl.isZero()
        && !now.isBefore(cached.loadedAt())
        && now.isBefore(cached.loadedAt().plus(ttl))) {
      return cached;
    }
    Snapshot loaded = load(now);
    if (loaded.signing() == null) {
      ensureKeyExists();
      loaded = load(clock.instant());
    }
    if (loaded.signing() == null) {
      throw new IllegalStateException("No signing key is active");
    }
    snapshot = loaded;
    return loaded;
  }

  private Snapshot load(Instant now) {
    List<JWK> jwks =
        repository.findPublished(now).stream()
            .map(key -> SigningAlgorithm.parse(key.algorithm()).toJwk(decodePublic(key), key.kid()))
            .toList();
    SigningKey signing = repository.findSigning(now).map(this::toSigningKey).orElse(null);
    return new Snapshot(now, signing, new JWKSet(jwks));
  }

  private PublicKey decodePublic(StoredKey key) {
    return SigningAlgorithm.parse(key.algorithm())
        .decodePublic(Base64.getDecoder().decode(key.publicKey()));
  }

  private SigningKey toSigningKey(StoredKey key) {
    SigningAlgorithm keyAlgorithm = SigningAlgorithm.parse(key.algorithm());
    byte[] pkcs8 = cipher.decrypt(key.privateKeyEnc(), key.kid());
    return new SigningKey(
        key.kid(), key.algorithm(), decodePublic(key), keyAlgorithm.decodePrivate(pkcs8));
  }
}
