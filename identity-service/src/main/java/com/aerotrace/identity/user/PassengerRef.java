package com.aerotrace.identity.user;

import java.security.SecureRandom;
import java.util.regex.Pattern;

/**
 * The pseudonymous passenger reference placed in tokens for row-level ownership: "P-" followed by
 * 20 random digits. It carries no personal data. The shape matches what the shared log masker
 * redacts, and references are never logged anyway.
 */
public final class PassengerRef {

  private static final Pattern FORMAT = Pattern.compile("P-[0-9]{20}");
  private static final SecureRandom RANDOM = new SecureRandom();

  private PassengerRef() {}

  public static String generate() {
    StringBuilder digits = new StringBuilder("P-");
    for (int i = 0; i < 20; i++) {
      digits.append(RANDOM.nextInt(10));
    }
    return digits.toString();
  }

  public static boolean isValid(String value) {
    return value != null && FORMAT.matcher(value).matches();
  }
}
