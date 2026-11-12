package com.aerotrace.identity.keys;

import com.aerotrace.identity.audit.AuthAuditLog;
import com.aerotrace.identity.audit.AuthEventType;
import com.aerotrace.identity.audit.AuthOutcome;
import com.aerotrace.identity.model.entity.SigningKeyEntity;
import com.aerotrace.identity.repository.SigningKeyRepository;
import com.aerotrace.identity.token.TokenProperties;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import java.security.KeyPair;
import java.security.PublicKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Generates, rotates and retires signing keys, and answers which key signs now and which keys are
 * published.
 *
 * <p>The newest activated key signs. A rotation publishes the new key at once but lets it start
 * signing only after {@code publishLead}, so verifiers have the key before the first token carries
 * its id. The key it replaces is retired once its last token can no longer be valid. Changes are
 * serialized across replicas by a database advisory lock, so replicas never create competing keys,
 * and all moments are judged by the database's clock.
 *
 * <p>The published key set is built from public keys only, so it keeps being served even if a
 * private key cannot be decrypted (a changed key-encryption key, a damaged row); signing then moves
 * to a freshly created key instead of failing every request. If the database cannot be reached the
 * last loaded keys are used for a bounded time.
 */
@Service
public class SigningKeyService {

  private static final Logger log = LoggerFactory.getLogger(SigningKeyService.class);

  /** Namespace of this service's advisory locks in the shared database. */
  private static final int LOCK_NAMESPACE = "aerotrace.identity".hashCode();

  private static final int LOCK_SIGNING_KEYS = 1;

  /** How many of the newest signing candidates are tried when looking for one that can be read. */
  private static final int CANDIDATES = 10;

  /** At most this many keys are created within the window to replace unreadable ones. */
  private static final int STORM_LIMIT = 3;

  private static final Duration STORM_WINDOW = Duration.ofMinutes(10);

  /** After a failed refresh, how long to keep serving the last loaded keys without asking again. */
  private static final Duration FAILURE_BACKOFF = Duration.ofSeconds(2);

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

  private final ReentrantLock refreshLock = new ReentrantLock();
  private volatile Snapshot snapshot;
  private volatile Instant lastFailureAt;

  SigningKeyService(
      SigningKeyRepository repository,
      KeyCipher cipher,
      KeyProperties properties,
      TokenProperties tokens,
      Clock clock,
      AuthAuditLog audit,
      TransactionTemplate transaction) {
    validateTimings(properties, tokens);
    this.repository = repository;
    this.cipher = cipher;
    this.properties = properties;
    this.tokens = tokens;
    this.clock = clock;
    this.audit = audit;
    this.transaction = transaction;
    this.algorithm = SigningAlgorithm.parse(properties.algorithm());
  }

  /**
   * Rotation is only safe when the settings fit together; a bad combination would silently bring
   * back "rotation invalidates tokens that have not expired", so it stops start-up instead.
   */
  static void validateTimings(KeyProperties keys, TokenProperties tokens) {
    Duration lifetime = tokens.ttl().plus(tokens.clockSkew());
    require(!tokens.ttl().isNegative() && !tokens.ttl().isZero(), "token.ttl must be positive");
    require(!tokens.clockSkew().isNegative(), "token.clock-skew must not be negative");
    require(!keys.cacheTtl().isNegative(), "keys.cache-ttl must not be negative");
    require(
        keys.maxStale().compareTo(keys.cacheTtl()) >= 0,
        "keys.max-stale must be at least keys.cache-ttl");
    // A replica holding keys this old can still sign with a key that has since been replaced. That
    // key is kept for the token lifetime plus the skew only, so its tokens must not outlive it.
    require(
        keys.maxStale().compareTo(tokens.clockSkew()) <= 0,
        "keys.max-stale must not be longer than token.clock-skew: a replica serving stale keys "
            + "could sign with a key that retires before the tokens it signs expire");
    // A verifier holding keys this old must still learn of a new key before a token carries it.
    require(
        keys.publishLead().compareTo(keys.maxStale()) >= 0,
        "keys.publish-lead must be at least keys.max-stale");
    require(
        keys.rotationInterval().compareTo(keys.publishLead().plus(lifetime)) > 0,
        "keys.rotation-interval must be longer than keys.publish-lead + token.ttl + "
            + "token.clock-skew, or keys would be replaced faster than they retire");
    require(
        !keys.maintenanceInterval().isNegative() && !keys.maintenanceInterval().isZero(),
        "keys.maintenance-interval must be positive");
  }

  private static void require(boolean condition, String message) {
    if (!condition) {
      throw new IllegalStateException("Invalid aerotrace.identity settings: " + message);
    }
  }

  /**
   * Makes sure there is a key that can sign now, creating one if not (first start, a wiped table,
   * or a signing key that cannot be decrypted). Safe to call from every replica at start-up.
   */
  public void ensureKeyExists() {
    transaction.executeWithoutResult(
        status -> {
          lock();
          ensureSigningKeyLocked(clock.instant());
        });
    snapshot = null;
  }

  /** The key to sign with now. */
  public SigningKey signingKey() {
    return current(true).signing();
  }

  /** The public keys to publish (current, upcoming and not-yet-retired previous keys). */
  public JWKSet publishedKeys() {
    return current(false).published();
  }

  /**
   * Introduces a new key. If one is already waiting to start signing, that one is returned instead
   * of stacking another.
   */
  public String rotate() {
    Rotation result =
        transaction.execute(
            status -> {
              lock();
              return rotateLocked(clock.instant());
            });
    return afterRotation(result);
  }

  /** Rotates when the newest key has been signing for the rotation interval. */
  public Optional<String> rotateIfDue() {
    Rotation result =
        transaction.execute(
            status -> {
              lock();
              Instant now = clock.instant();
              Optional<Instant> newest = repository.newestActivation();
              if (newest.isEmpty()) {
                return new Rotation(create(now, now), true);
              }
              boolean waiting = !repository.findPending(now, Limit.of(1)).isEmpty();
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

  private void lock() {
    repository.lock(LOCK_NAMESPACE, LOCK_SIGNING_KEYS);
  }

  /**
   * Creates a signing key unless one that can sign already exists. A newer key this replica cannot
   * read is left alone when an older one can be used: another replica may hold the right
   * key-encryption key for it. Only when nothing at all can be read are the unreadable keys retired
   * (their tokens stay verifiable until they expire) and a replacement created, and never more than
   * a few times in a short while, so replicas that disagree about the key-encryption key cannot
   * keep replacing each other's keys.
   */
  private void ensureSigningKeyLocked(Instant now) {
    List<SigningKeyEntity> candidates = repository.findSigning(now, Limit.of(CANDIDATES));
    List<String> unreadable = new ArrayList<>();
    for (SigningKeyEntity candidate : candidates) {
      if (decrypt(candidate).isPresent()) {
        return;
      }
      unreadable.add(candidate.getKid());
    }
    if (repository.countCreatedSince(now.minus(STORM_WINDOW)) >= STORM_LIMIT) {
      throw new IllegalStateException(
          "Signing keys keep failing to decrypt and "
              + STORM_LIMIT
              + " were created in the last "
              + STORM_WINDOW.toMinutes()
              + " minutes. Check that every replica has the same key-encryption key"
              + " (IDENTITY_KEY_ENCRYPTION_KEY).");
    }
    if (!unreadable.isEmpty()) {
      repository.retireKeys(unreadable, now.plus(tokens.ttl().plus(tokens.clockSkew())));
    }
    String kid = create(now, now);
    log.info("Created signing key {} because none could sign", kid);
  }

  private Rotation rotateLocked(Instant now) {
    List<SigningKeyEntity> waiting = repository.findPending(now, Limit.of(1));
    if (!waiting.isEmpty()) {
      return new Rotation(waiting.get(0).getKid(), false);
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
    KeyPair pair = algorithm.generate();
    PublicKey publicKey = pair.getPublic();
    String kid = thumbprint(publicKey);
    repository.save(
        new SigningKeyEntity(
            kid,
            algorithm.name(),
            Base64.getEncoder().encodeToString(publicKey.getEncoded()),
            cipher.encrypt(pair.getPrivate().getEncoded(), kid),
            createdAt,
            activateAt));
    return kid;
  }

  /** The RFC 7638 JWK thumbprint, so a key id is derived from the key and never reused. */
  private String thumbprint(PublicKey publicKey) {
    try {
      return algorithm.toJwk(publicKey, null).computeThumbprint().toString();
    } catch (JOSEException e) {
      throw new IllegalStateException("Could not derive a key id", e);
    }
  }

  private Snapshot current(boolean needSigningKey) {
    Snapshot cached = snapshot;
    Instant seen = clock.instant();
    if (usable(cached, seen, needSigningKey)) {
      return cached;
    }
    // When there is a last loaded set that is still fresh enough to serve, nobody has to wait for
    // the database: one caller refreshes, the others carry on with what was loaded, and after a
    // failure nobody asks again for a moment.
    boolean canServeStale =
        cached != null
            && withinMaxStale(cached, seen)
            && (!needSigningKey || cached.signing() != null);
    if (canServeStale) {
      Instant failedAt = lastFailureAt;
      if (failedAt != null && seen.isBefore(failedAt.plus(FAILURE_BACKOFF))) {
        return cached;
      }
      if (!refreshLock.tryLock()) {
        return cached;
      }
    } else {
      refreshLock.lock();
    }
    try {
      cached = snapshot;
      Instant now = clock.instant();
      if (usable(cached, now, needSigningKey)) {
        return cached;
      }
      try {
        Snapshot loaded = load(now);
        // Heal when signing is needed and impossible, or when there is no key at all (a wiped
        // table would otherwise leave verifiers with an empty key set).
        boolean broken =
            needSigningKey ? loaded.signing() == null : loaded.published().getKeys().isEmpty();
        if (broken) {
          ensureKeyExists();
          loaded = load(clock.instant());
          if (needSigningKey && loaded.signing() == null) {
            throw new IllegalStateException("No signing key is active");
          }
        }
        snapshot = loaded;
        lastFailureAt = null;
        return loaded;
      } catch (RuntimeException e) {
        lastFailureAt = now;
        if (cached != null
            && withinMaxStale(cached, now)
            && (!needSigningKey || cached.signing() != null)) {
          log.warn(
              "Could not refresh the signing keys; using the last loaded set ({})",
              e.getClass().getSimpleName());
          return cached;
        }
        throw e;
      }
    } finally {
      refreshLock.unlock();
    }
  }

  private boolean usable(Snapshot cached, Instant now, boolean needSigningKey) {
    if (cached == null || (needSigningKey && cached.signing() == null)) {
      return false;
    }
    Duration ttl = properties.cacheTtl();
    return !ttl.isZero()
        && !now.isBefore(cached.loadedAt())
        && now.isBefore(cached.loadedAt().plus(ttl));
  }

  private boolean withinMaxStale(Snapshot cached, Instant now) {
    return !now.isBefore(cached.loadedAt())
        && now.isBefore(cached.loadedAt().plus(properties.maxStale()));
  }

  private Snapshot load(Instant now) {
    // One damaged row must not take the whole key set down: skip it, say so, keep the rest.
    List<JWK> jwks = new ArrayList<>();
    for (SigningKeyEntity key : repository.findPublished(now)) {
      try {
        jwks.add(SigningAlgorithm.parse(key.getAlgorithm()).toJwk(decodePublic(key), key.getKid()));
      } catch (RuntimeException e) {
        log.error(
            "Published signing key {} is unreadable and is left out ({})",
            key.getKid(),
            e.getClass().getSimpleName());
      }
    }
    // The newest key this replica can actually use, not merely the newest key.
    SigningKey signing =
        repository.findSigning(now, Limit.of(CANDIDATES)).stream()
            .map(this::decrypt)
            .flatMap(Optional::stream)
            .findFirst()
            .orElse(null);
    return new Snapshot(now, signing, new JWKSet(jwks));
  }

  private PublicKey decodePublic(SigningKeyEntity key) {
    return SigningAlgorithm.parse(key.getAlgorithm())
        .decodePublic(Base64.getDecoder().decode(key.getPublicKey()));
  }

  /** The key with its private part, or empty (and an error logged) if it cannot be decrypted. */
  private Optional<SigningKey> decrypt(SigningKeyEntity key) {
    try {
      SigningAlgorithm keyAlgorithm = SigningAlgorithm.parse(key.getAlgorithm());
      byte[] pkcs8 = cipher.decrypt(key.getPrivateKeyEnc(), key.getKid());
      return Optional.of(
          new SigningKey(
              key.getKid(),
              key.getAlgorithm(),
              decodePublic(key),
              keyAlgorithm.decodePrivate(pkcs8)));
    } catch (RuntimeException e) {
      log.error(
          "Signing key {} cannot be used to sign (was the key-encryption key changed, or the row "
              + "damaged?): {}",
          key.getKid(),
          e.getClass().getSimpleName());
      return Optional.empty();
    }
  }
}
