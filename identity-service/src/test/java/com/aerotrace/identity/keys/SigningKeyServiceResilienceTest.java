package com.aerotrace.identity.keys;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aerotrace.identity.audit.AuthAuditLog;
import com.aerotrace.identity.model.entity.SigningKeyEntity;
import com.aerotrace.identity.repository.SigningKeyRepository;
import com.aerotrace.identity.support.MutableClock;
import com.aerotrace.identity.token.TokenProperties;
import java.security.KeyPair;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** What the key service does when the database is slow, busy or away, or a key cannot be read. */
class SigningKeyServiceResilienceTest {

  private static final Instant START = Instant.parse("2030-01-01T00:00:00Z");
  private static final String KEK = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=";
  private static final String OTHER_KEK = "H4SJWYYGmkU3gfNFSiLyx2ETcm3GWvtbH8KLzF6vYRk=";

  private final SigningKeyRepository repository = mock(SigningKeyRepository.class);
  private final MutableClock clock = new MutableClock(START);
  private final KeyCipher cipher = KeyCipher.fromBase64(KEK);
  private SigningKeyEntity good;
  private SigningKeyService service;

  private SigningKeyEntity key(String kid, KeyCipher encryptedWith, Instant activateAt) {
    KeyPair pair = SigningAlgorithm.RS256.generate();
    return new SigningKeyEntity(
        kid,
        "RS256",
        Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()),
        encryptedWith.encrypt(pair.getPrivate().getEncoded(), kid),
        activateAt,
        activateAt);
  }

  @BeforeEach
  void setUp() {
    good = key("kid-1", cipher, START);
    when(repository.findPublished(any())).thenReturn(List.of(good));
    when(repository.findSigning(any(), any())).thenReturn(List.of(good));
    KeyProperties keys =
        new KeyProperties(
            "RS256",
            KEK,
            Duration.ofDays(30),
            Duration.ofMinutes(10),
            Duration.ofSeconds(10),
            Duration.ofSeconds(30),
            Duration.ofMinutes(5));
    TokenProperties tokens =
        new TokenProperties(
            "http://identity.test", "aerotrace-api", Duration.ofMinutes(15), Duration.ofMinutes(1));
    service =
        new SigningKeyService(
            repository,
            cipher,
            keys,
            tokens,
            clock,
            mock(AuthAuditLog.class),
            new TransactionTemplate(mock(PlatformTransactionManager.class)));
  }

  // --- the database is away ------------------------------------------------------------------

  @Test
  void keepsSigningWithTheLastLoadedKeyWhileTheDatabaseIsAway() {
    assertThat(service.signingKey().kid()).isEqualTo("kid-1");
    when(repository.findPublished(any())).thenThrow(new DataAccessResourceFailureException("down"));

    clock.advance(Duration.ofSeconds(20));

    assertThat(service.signingKey().kid()).isEqualTo("kid-1");
    assertThat(service.publishedKeys().getKeyByKeyId("kid-1")).isNotNull();
  }

  @Test
  void stopsServingStaleKeysOnceTheAllowanceIsUsedUp() {
    service.signingKey();
    when(repository.findPublished(any())).thenThrow(new DataAccessResourceFailureException("down"));

    clock.advance(Duration.ofSeconds(31));

    assertThatThrownBy(() -> service.signingKey())
        .isInstanceOf(DataAccessResourceFailureException.class);
    assertThatThrownBy(() -> service.publishedKeys())
        .isInstanceOf(DataAccessResourceFailureException.class);
  }

  @Test
  void failsAtOnceWhenThereIsNothingStaleToFallBackOn() {
    when(repository.findPublished(any())).thenThrow(new DataAccessResourceFailureException("down"));

    assertThatThrownBy(() -> service.signingKey())
        .isInstanceOf(DataAccessResourceFailureException.class);
  }

  @Test
  void aFailingDatabaseIsNotAskedAgainOnEveryRequest() {
    service.signingKey();
    when(repository.findPublished(any())).thenThrow(new DataAccessResourceFailureException("down"));
    clock.advance(Duration.ofSeconds(20));

    for (int i = 0; i < 10; i++) {
      assertThat(service.signingKey().kid()).isEqualTo("kid-1");
    }

    // The first load, and a single failed attempt: the other nine calls backed off.
    verify(repository, times(2)).findPublished(any());

    clock.advance(Duration.ofSeconds(3));
    service.signingKey();
    verify(repository, times(3)).findPublished(any());
  }

  @Test
  void aBurstAfterTheCacheExpiresRefreshesOnlyOnce() throws Exception {
    service.signingKey();
    clock.advance(Duration.ofSeconds(11));
    when(repository.findPublished(any()))
        .thenAnswer(
            invocation -> {
              Thread.sleep(150);
              return List.of(good);
            });
    int callers = 8;
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(callers);
    try {
      List<Future<String>> results = new java.util.ArrayList<>();
      for (int i = 0; i < callers; i++) {
        results.add(
            pool.submit(
                () -> {
                  start.await();
                  return service.signingKey().kid();
                }));
      }
      start.countDown();
      for (Future<String> result : results) {
        assertThat(result.get()).isEqualTo("kid-1");
      }
    } finally {
      pool.shutdownNow();
    }

    // The first load, plus a single refresh shared by all eight callers.
    verify(repository, times(2)).findPublished(any());
  }

  @Test
  void aSlowRefreshDoesNotMakeTheOtherCallersWait() throws Exception {
    service.signingKey();
    clock.advance(Duration.ofSeconds(11));
    CountDownLatch refreshing = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    when(repository.findPublished(any()))
        .thenAnswer(
            invocation -> {
              refreshing.countDown();
              release.await();
              return List.of(good);
            });
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      Future<String> slow = pool.submit(() -> service.signingKey().kid());
      refreshing.await();

      Future<String> other = pool.submit(() -> service.signingKey().kid());

      // Served from the last loaded keys while the first caller is still waiting on the database.
      assertThat(other.get(2, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo("kid-1");
      release.countDown();
      assertThat(slow.get()).isEqualTo("kid-1");
    } finally {
      pool.shutdownNow();
    }
  }

  // --- a key that cannot be read ------------------------------------------------------------

  @Test
  void signsWithTheNewestKeyThatCanBeReadNotJustTheNewest() {
    SigningKeyEntity unreadable = key("kid-2", KeyCipher.fromBase64(OTHER_KEK), START);
    when(repository.findPublished(any())).thenReturn(List.of(good, unreadable));
    when(repository.findSigning(any(), any())).thenReturn(List.of(unreadable, good));

    assertThat(service.signingKey().kid()).isEqualTo("kid-1");

    // Another replica may be able to read the newest one; nothing is created or retired.
    verify(repository, never()).save(any());
    verify(repository, never()).retireKeys(anyCollection(), any());
  }

  @Test
  void whenNoKeyCanBeReadANewOneIsCreatedAndTheUnreadableOnesAreRetired() {
    SigningKeyEntity unreadable = key("kid-broken", KeyCipher.fromBase64(OTHER_KEK), START);
    AtomicReference<SigningKeyEntity> created = new AtomicReference<>();
    when(repository.save(any()))
        .thenAnswer(
            invocation -> {
              created.set(invocation.getArgument(0));
              return created.get();
            });
    when(repository.findSigning(any(), any()))
        .thenAnswer(
            invocation ->
                created.get() == null ? List.of(unreadable) : List.of(created.get(), unreadable));
    when(repository.findPublished(any()))
        .thenAnswer(
            invocation ->
                created.get() == null ? List.of(unreadable) : List.of(unreadable, created.get()));

    String kid = service.signingKey().kid();

    assertThat(kid).isNotEqualTo("kid-broken");
    ArgumentCaptor<Instant> retireAt = ArgumentCaptor.forClass(Instant.class);
    verify(repository)
        .retireKeys(org.mockito.ArgumentMatchers.eq(List.of("kid-broken")), retireAt.capture());
    // Its tokens may still be in flight: kept published for the token lifetime plus the skew.
    assertThat(retireAt.getValue()).isEqualTo(START.plus(Duration.ofMinutes(16)));
  }

  @Test
  void keyCreationStopsWhenKeysKeepFailingToBeReadSoReplicasCannotStartAStorm() {
    SigningKeyEntity unreadable = key("kid-broken", KeyCipher.fromBase64(OTHER_KEK), START);
    when(repository.findSigning(any(), any())).thenReturn(List.of(unreadable));
    when(repository.findPublished(any())).thenReturn(List.of(unreadable));
    when(repository.countCreatedSince(any())).thenReturn(3L);

    assertThatThrownBy(() -> service.signingKey())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("key-encryption key");

    verify(repository, never()).save(any());
    verify(repository, never()).retireKeys(anyCollection(), any());
  }

  @Test
  void oneDamagedPublicKeyRowIsSkippedInsteadOfTakingEverythingDown() {
    SigningKeyEntity damaged =
        new SigningKeyEntity("kid-damaged", "RS256", "%%not-base64%%", null, START, START);
    when(repository.findPublished(any())).thenReturn(List.of(damaged, good));

    assertThat(service.publishedKeys().getKeys()).hasSize(1);
    assertThat(service.publishedKeys().getKeyByKeyId("kid-1")).isNotNull();
    assertThat(service.signingKey().kid()).isEqualTo("kid-1");
  }

  @Test
  void aSigningRowWithGarbageInItsPrivateKeyIsTreatedAsUnreadableNotAsAnError() {
    SigningKeyEntity garbled =
        new SigningKeyEntity(
            "kid-garbled",
            "RS256",
            good.getPublicKey(),
            cipher.encrypt(new byte[] {1, 2, 3}, "kid-garbled"),
            START,
            START);
    when(repository.findSigning(any(), any())).thenReturn(List.of(garbled, good));

    assertThat(service.signingKey().kid()).isEqualTo("kid-1");
    verify(repository, never()).countCreatedSince(any());
  }
}
