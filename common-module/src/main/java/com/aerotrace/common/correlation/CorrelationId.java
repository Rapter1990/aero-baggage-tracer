package com.aerotrace.common.correlation;

import java.util.UUID;
import java.util.regex.Pattern;

/** Constants and helpers for the correlation id carried on every request, log line and event. */
public final class CorrelationId {

  public static final String HEADER = "X-Correlation-Id";
  public static final String MDC_KEY = "correlationId";

  private static final Pattern SAFE = Pattern.compile("[A-Za-z0-9._-]{1,64}");

  private CorrelationId() {}

  public static String generate() {
    return UUID.randomUUID().toString();
  }

  /** Accepts only short, header-safe values so clients cannot inject log or header content. */
  public static boolean isValid(String value) {
    return value != null && SAFE.matcher(value).matches();
  }
}
