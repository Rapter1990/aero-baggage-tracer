package com.aerotrace.identity.user;

import com.aerotrace.identity.constant.UserRules;
import java.security.SecureRandom;
import java.util.regex.Pattern;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * The pseudonymous passenger reference placed in tokens for row-level ownership: "P-" followed by
 * 20 random digits. It carries no personal data. The shape matches what the shared log masker
 * redacts, and references are never logged anyway.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class PassengerRef {

  private static final Pattern FORMAT = Pattern.compile(UserRules.PASSENGER_REF_PATTERN);
  private static final SecureRandom RANDOM = new SecureRandom();

  public static String generate() {
    StringBuilder reference = new StringBuilder(UserRules.PASSENGER_REF_PREFIX);
    for (int i = 0; i < UserRules.PASSENGER_REF_DIGITS; i++) {
      reference.append(RANDOM.nextInt(10));
    }
    return reference.toString();
  }

  public static boolean isValid(String value) {
    return value != null && FORMAT.matcher(value).matches();
  }
}
