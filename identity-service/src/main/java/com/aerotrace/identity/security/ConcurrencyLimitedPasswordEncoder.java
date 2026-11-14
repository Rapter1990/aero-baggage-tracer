package com.aerotrace.identity.security;

import com.aerotrace.common.error.ApiException;
import com.aerotrace.identity.constant.ApiMessages;
import com.aerotrace.identity.constant.AuthConstants;
import com.aerotrace.identity.constant.MetricNames;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Lets only a few password hashes run at the same time. A BCrypt hash costs a lot of CPU on
 * purpose, so a flood of sign-ins would otherwise use every core and starve the rest of the
 * service. A call that cannot get a slot within {@code maxWait} is refused with a 429 and a
 * Retry-After, instead of queueing without limit.
 */
public class ConcurrencyLimitedPasswordEncoder implements PasswordEncoder {

  private final PasswordEncoder delegate;
  private final Semaphore slots;
  private final Duration maxWait;
  private final MeterRegistry meters;

  public ConcurrencyLimitedPasswordEncoder(
      PasswordEncoder delegate, int maxConcurrent, Duration maxWait, MeterRegistry meters) {
    if (maxConcurrent < 1) {
      throw new IllegalArgumentException("maxConcurrent must be at least 1");
    }
    this.delegate = delegate;
    // Fair, so a waiting caller is not overtaken again and again.
    this.slots = new Semaphore(maxConcurrent, true);
    this.maxWait = maxWait;
    this.meters = meters;
  }

  @Override
  public String encode(CharSequence rawPassword) {
    return withSlot(() -> delegate.encode(rawPassword));
  }

  @Override
  public boolean matches(CharSequence rawPassword, String encodedPassword) {
    return withSlot(() -> delegate.matches(rawPassword, encodedPassword));
  }

  @Override
  public boolean upgradeEncoding(String encodedPassword) {
    return delegate.upgradeEncoding(encodedPassword);
  }

  private <T> T withSlot(Supplier<T> hash) {
    boolean acquired;
    try {
      acquired = slots.tryAcquire(maxWait.toMillis(), TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw busy();
    }
    if (!acquired) {
      throw busy();
    }
    try {
      return hash.get();
    } finally {
      slots.release();
    }
  }

  private ApiException busy() {
    meters
        .counter(MetricNames.AUTH_REFUSED, MetricNames.TAG_REASON, MetricNames.REASON_BUSY)
        .increment();
    return ApiException.rateLimited(ApiMessages.SERVICE_BUSY, AuthConstants.BUSY_RETRY_AFTER);
  }
}
