package com.aerotrace.identity.constant;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/** Metric names and labels. Only the outcome is ever a label: never a user or a credential. */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class MetricNames {

  public static final String TOKEN_REQUESTS = "identity.auth.token.requests";
  public static final String TAG_OUTCOME = "outcome";
  public static final String OUTCOME_SUCCESS = "success";
  public static final String OUTCOME_FAILURE = "failure";

  /** Calls refused before any password was checked: locked, throttled or busy. */
  public static final String AUTH_REFUSED = "identity.auth.refused";

  public static final String TAG_REASON = "reason";
  public static final String REASON_LOCKED = "locked";
  public static final String REASON_THROTTLED = "throttled";
  public static final String REASON_BUSY = "busy";
}
