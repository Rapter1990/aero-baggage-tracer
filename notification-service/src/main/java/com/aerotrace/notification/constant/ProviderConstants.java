package com.aerotrace.notification.constant;

import lombok.experimental.UtilityClass;

/** Constants of the delivery provider (the mock keeps a bounded history). */
@UtilityClass
public class ProviderConstants {

  /** The error code of a failure the mock was told to produce. */
  public static final String MOCK_FAILURE = "PROVIDER_UNAVAILABLE";

  /** How many attempted and sent ids are remembered; older ones are forgotten first. */
  public static final int HISTORY_LIMIT = 1_000;
}
