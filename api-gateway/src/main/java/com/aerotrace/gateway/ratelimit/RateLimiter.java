package com.aerotrace.gateway.ratelimit;

/** Decides whether a caller may make another request right now. */
@FunctionalInterface
public interface RateLimiter {

  Decision tryAcquire(String key);

  /**
   * @param allowed whether the request may proceed
   * @param remaining tokens left in the bucket, or -1 when unknown
   * @param retryAfterSeconds when refused, how long until a token is available (0 when allowed)
   */
  record Decision(boolean allowed, long remaining, long retryAfterSeconds) {

    /** A decision without a computed wait: a refusal then says to retry after one second. */
    public Decision(boolean allowed, long remaining) {
      this(allowed, remaining, allowed ? 0 : 1);
    }
  }
}
