package com.aerotrace.common.logging;

import java.util.regex.Pattern;

/** Removes secrets and passenger identifiers from text before it is logged. */
public final class LogMasker {

  public static final String REDACTED = "[REDACTED]";

  private static final Pattern BEARER = Pattern.compile("(?i)bearer\\s+[A-Za-z0-9._~+/=-]+");
  private static final Pattern JWT =
      Pattern.compile("eyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]*");
  private static final Pattern EMAIL =
      Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
  private static final Pattern PASSENGER_REF = Pattern.compile("\\bP-\\d+\\b");

  private LogMasker() {}

  public static String mask(String text) {
    if (text == null || text.isEmpty()) {
      return text;
    }
    String out = BEARER.matcher(text).replaceAll(REDACTED);
    out = JWT.matcher(out).replaceAll(REDACTED);
    out = EMAIL.matcher(out).replaceAll(REDACTED);
    return PASSENGER_REF.matcher(out).replaceAll(REDACTED);
  }

  /** Passenger references are never logged, not even partially. */
  public static String maskPassengerRef(String ref) {
    return REDACTED;
  }
}
