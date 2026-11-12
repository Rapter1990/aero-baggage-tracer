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
}
