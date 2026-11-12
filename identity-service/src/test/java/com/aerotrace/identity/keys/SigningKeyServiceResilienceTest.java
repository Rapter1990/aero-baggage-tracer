package com.aerotrace.identity.keys;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** What the key service does when the database is slow, busy or away. */
class SigningKeyServiceResilienceTest {

  private static final Instant START = Instant.parse("2030-01-01T00:00:00Z");
  private static final String KEK = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=";

  private final SigningKeyRepository repository = mock(SigningKeyRepository.class);
  private final MutableClock clock = new MutableClock(START);
  private SigningKeyEntity entity;
  private SigningKeyService service;

  @BeforeEach
  void setUp() {
    KeyCipher cipher = KeyCipher.fromBase64(KEK);
    KeyPair pair = SigningAlgorithm.RS256.generate();
    entity =
        new SigningKeyEntity(
            "kid-1",
            "RS256",
            Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()),
            cipher.encrypt(pair.getPrivate().getEncoded(), "kid-1"),
            START,
            START);
    when(repository.findPublished(any())).thenReturn(List.of(entity));
    when(repository.findSigning(any(), any())).thenReturn(List.of(entity));
    KeyProperties keys =
        new KeyProperties(
            "RS256",
            KEK,
            Duration.ofDays(30),
            Duration.ofMinutes(10),
            Duration.ofSeconds(10),
            Duration.ofMinutes(5),
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

  @Test
  void keepsSigningWithTheLastLoadedKeyWhileTheDatabaseIsAway() {
    assertThat(service.signingKey().kid()).isEqualTo("kid-1");
    when(repository.findPublished(any())).thenThrow(new DataAccessResourceFailureException("down"));

    clock.advance(Duration.ofSeconds(30));

    assertThat(service.signingKey().kid()).isEqualTo("kid-1");
    assertThat(service.publishedKeys().getKeyByKeyId("kid-1")).isNotNull();
  }

  @Test
  void stopsServingStaleKeysOnceTheAllowanceIsUsedUp() {
    service.signingKey();
    when(repository.findPublished(any())).thenThrow(new DataAccessResourceFailureException("down"));

    clock.advance(Duration.ofMinutes(5).plusSeconds(1));

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
  void aBurstAfterTheCacheExpiresRefreshesOnlyOnce() throws Exception {
    service.signingKey();
    clock.advance(Duration.ofSeconds(11));
    when(repository.findPublished(any()))
        .thenAnswer(
            invocation -> {
              Thread.sleep(150);
              return List.of(entity);
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
}
