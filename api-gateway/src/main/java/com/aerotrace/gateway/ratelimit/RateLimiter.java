package com.aerotrace.gateway.ratelimit;

/** Decides whether a caller may make another request right now. */
@FunctionalInterface
public interface RateLimiter {

  Decision tryAcquire(String key);

  /**
   * @param allowed whether the request may proceed
   * @param remaining tokens left in the bucket, or -1 when unknown
   */
  record Decision(boolean allowed, long remaining) {}
}
