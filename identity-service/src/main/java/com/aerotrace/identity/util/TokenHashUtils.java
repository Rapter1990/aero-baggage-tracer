package com.aerotrace.identity.util;

import com.aerotrace.identity.constant.AuthConstants;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * Generating secure random tokens and hashing them.
 *
 * <p>Used for refresh tokens and password reset tokens. The raw token is handed to the client once;
 * only its hash is stored, so a copy of the database is of no use to someone who wants to sign in.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class TokenHashUtils {

  private static final SecureRandom SECURE_RANDOM = new SecureRandom();

  /** A secure random token: 48 random bytes as base64url without padding. */
  public static String randomToken() {
    byte[] bytes = new byte[AuthConstants.RANDOM_TOKEN_BYTES];
    SECURE_RANDOM.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  /** The SHA-256 hash of a token, as 64 hexadecimal characters. */
  public static String sha256(String token) {
    try {
      MessageDigest digest = MessageDigest.getInstance(AuthConstants.TOKEN_HASH_ALGORITHM);
      return HexFormat.of().formatHex(digest.digest(token.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is not available", e);
    }
  }
}
