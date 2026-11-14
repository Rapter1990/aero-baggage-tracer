package com.aerotrace.identity.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aerotrace.common.error.ApiException;
import com.aerotrace.common.error.ErrorCode;
import com.aerotrace.identity.constant.MetricNames;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

/** Password hashing is the expensive step of every sign-in, so only a few run at once. */
class ConcurrencyLimitedPasswordEncoderTest {

  private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

  /** Stands in for BCrypt: waits on a latch (when given), counts how many run at once. */
  private static final class SlowEncoder implements PasswordEncoder {
    final AtomicInteger running = new AtomicInteger();
    final AtomicInteger peak = new AtomicInteger();
    volatile CountDownLatch hold = new CountDownLatch(0);
    volatile boolean fail;

    private void work() {
      int now = running.incrementAndGet();
      peak.accumulateAndGet(now, Math::max);
      try {
        if (fail) {
          throw new IllegalStateException("boom");
        }
        hold.await();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      } finally {
        running.decrementAndGet();
      }
    }

    @Override
    public String encode(CharSequence rawPassword) {
      work();
      return "hash:" + rawPassword;
    }

    @Override
    public boolean matches(CharSequence rawPassword, String encodedPassword) {
      work();
      return encodedPassword.equals("hash:" + rawPassword);
    }
  }

  @Test
  void passesTheResultsOfTheRealEncoderThrough() {
    var encoder =
        new ConcurrencyLimitedPasswordEncoder(new SlowEncoder(), 2, Duration.ofSeconds(1), meters);

    assertThat(encoder.encode("pw")).isEqualTo("hash:pw");
    assertThat(encoder.matches("pw", "hash:pw")).isTrue();
    assertThat(encoder.matches("other", "hash:pw")).isFalse();
  }

  @Test
  void neverRunsMoreHashesAtOnceThanAllowed() throws Exception {
    SlowEncoder slow = new SlowEncoder();
    slow.hold = new CountDownLatch(1);
    var encoder = new ConcurrencyLimitedPasswordEncoder(slow, 2, Duration.ofSeconds(5), meters);
    ExecutorService pool = Executors.newFixedThreadPool(6);
    List<Future<String>> calls = new ArrayList<>();
    for (int i = 0; i < 6; i++) {
      calls.add(pool.submit(() -> encoder.encode("pw")));
    }
    Thread.sleep(300);

    assertThat(slow.running.get()).isEqualTo(2);
    slow.hold.countDown();
    for (Future<String> call : calls) {
      assertThat(call.get(5, TimeUnit.SECONDS)).isEqualTo("hash:pw");
    }
    pool.shutdown();
    assertThat(slow.peak.get()).isEqualTo(2);
  }

  @Test
  void refusesWith429AndRetryAfterWhenNoSlotFreesUpInTime() throws Exception {
    SlowEncoder slow = new SlowEncoder();
    slow.hold = new CountDownLatch(1);
    var encoder = new ConcurrencyLimitedPasswordEncoder(slow, 1, Duration.ofMillis(100), meters);
    ExecutorService pool = Executors.newSingleThreadExecutor();
    Future<String> occupying = pool.submit(() -> encoder.encode("first"));
    Thread.sleep(200);

    assertThatThrownBy(() -> encoder.matches("second", "hash:second"))
        .isInstanceOfSatisfying(
            ApiException.class,
            e -> {
              assertThat(e.errorCode()).isEqualTo(ErrorCode.RATE_LIMITED);
              assertThat(e.retryAfter()).isNotNull().isGreaterThanOrEqualTo(Duration.ofSeconds(1));
            });
    assertThat(
            meters
                .counter(MetricNames.AUTH_REFUSED, MetricNames.TAG_REASON, MetricNames.REASON_BUSY)
                .count())
        .isEqualTo(1.0);

    slow.hold.countDown();
    occupying.get(5, TimeUnit.SECONDS);
    pool.shutdown();
  }

  @Test
  void aFailingHashGivesItsSlotBack() {
    SlowEncoder slow = new SlowEncoder();
    var encoder = new ConcurrencyLimitedPasswordEncoder(slow, 1, Duration.ofMillis(100), meters);

    slow.fail = true;
    assertThatThrownBy(() -> encoder.encode("pw")).isInstanceOf(IllegalStateException.class);
    slow.fail = false;

    assertThat(encoder.encode("pw")).isEqualTo("hash:pw");
  }

  @Test
  void aWaitingCallerThatIsInterruptedGivesUpAndKeepsTheInterruptFlag() throws Exception {
    SlowEncoder slow = new SlowEncoder();
    slow.hold = new CountDownLatch(1);
    var encoder = new ConcurrencyLimitedPasswordEncoder(slow, 1, Duration.ofSeconds(10), meters);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    Future<String> occupying = pool.submit(() -> encoder.encode("first"));
    Thread.sleep(200);
    var interrupted = new java.util.concurrent.atomic.AtomicBoolean();
    var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
    Thread waiter =
        new Thread(
            () -> {
              try {
                encoder.encode("second");
              } catch (Throwable t) {
                failure.set(t);
                interrupted.set(Thread.currentThread().isInterrupted());
              }
            });
    waiter.start();
    Thread.sleep(200);

    waiter.interrupt();
    waiter.join(5_000);

    assertThat(failure.get()).isInstanceOf(ApiException.class);
    assertThat(interrupted.get()).isTrue();
    slow.hold.countDown();
    occupying.get(5, TimeUnit.SECONDS);
    pool.shutdown();
  }
}
