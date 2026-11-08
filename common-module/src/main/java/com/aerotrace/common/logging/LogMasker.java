package com.aerotrace.common.logging;

import java.util.regex.Pattern;

/** Removes secrets and passenger identifiers from text before it is logged. */
public final class LogMasker {

  public static final String REDACTED = "[REDACTED]";

  // Quantifiers are bounded so long hostile log lines cannot cause quadratic regex backtracking.
  private static final Pattern BEARER =
      Pattern.compile("(?i)bearer\\s{1,8}[A-Za-z0-9._~+/=-]{1,4096}");
  private static final Pattern JWT =
      Pattern.compile("eyJ[A-Za-z0-9_-]{1,4096}\\.[A-Za-z0-9_-]{1,4096}\\.[A-Za-z0-9_-]{0,4096}");
  private static final Pattern EMAIL =
      Pattern.compile("[A-Za-z0-9._%+-]{1,64}@[A-Za-z0-9.-]{1,255}\\.[A-Za-z]{2,24}");
  private static final Pattern PASSENGER_REF = Pattern.compile("(?i)\\bP-\\d+[A-Za-z0-9]*");
  private static final Pattern BASIC = Pattern.compile("(?i)basic\\s{1,8}[A-Za-z0-9+/=]{1,4096}");
  private static final Pattern SECRET_FIELD =
      Pattern.compile(
          "(?i)(access_token|refresh_token|id_token|client_secret|password|secret)"
              + "(\"?\\s{0,4}[=:]\\s{0,4}\"?)[^\\s\"&,;]{1,4096}");

  /** Longest text masked in full; anything beyond is truncated so hostile input stays cheap. */
  private static final int MAX_LENGTH = 16_384;

  private LogMasker() {}

  public static String mask(String text) {
    if (text == null || text.isEmpty()) {
      return text;
    }
    String input =
        text.length() > MAX_LENGTH ? text.substring(0, MAX_LENGTH) + "...[TRUNCATED]" : text;
    String out = BEARER.matcher(input).replaceAll(REDACTED);
    out = BASIC.matcher(out).replaceAll(REDACTED);
    out = SECRET_FIELD.matcher(out).replaceAll("$1$2" + REDACTED);
    out = JWT.matcher(out).replaceAll(REDACTED);
    out = EMAIL.matcher(out).replaceAll(REDACTED);
    return PASSENGER_REF.matcher(out).replaceAll(REDACTED);
  }

  /** Passenger references are never logged, not even partially. */
  public static String maskPassengerRef(String ref) {
    return REDACTED;
  }
}
