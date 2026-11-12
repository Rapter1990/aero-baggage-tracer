package com.aerotrace.identity.keys;

import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Encrypts private keys for storage with AES-256-GCM. The key id is bound in as associated data, so
 * a ciphertext copied onto another key's row fails to decrypt. The stored form is base64(nonce ||
 * ciphertext-and-tag).
 */
public final class KeyCipher {

  private static final int KEY_BYTES = 32;
  private static final int NONCE_BYTES = 12;
  private static final int TAG_BITS = 128;

  private final SecretKey kek;
  private final SecureRandom random = new SecureRandom();

  private KeyCipher(SecretKey kek) {
    this.kek = kek;
  }

  /** Builds the cipher from base64 of a 32-byte key. Messages never repeat the key. */
  public static KeyCipher fromBase64(String encoded) {
    if (encoded == null || encoded.isBlank()) {
      throw new IllegalStateException("aerotrace.identity.keys.encryption-key must be set");
    }
    byte[] key;
    try {
      key = Base64.getDecoder().decode(encoded.trim());
    } catch (IllegalArgumentException e) {
      throw new IllegalStateException("aerotrace.identity.keys.encryption-key is not base64");
    }
    if (key.length != KEY_BYTES) {
      throw new IllegalStateException(
          "aerotrace.identity.keys.encryption-key must decode to " + KEY_BYTES + " bytes");
    }
    return new KeyCipher(new SecretKeySpec(key, "AES"));
  }

  String encrypt(byte[] plain, String kid) {
    try {
      byte[] nonce = new byte[NONCE_BYTES];
      random.nextBytes(nonce);
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.ENCRYPT_MODE, kek, new GCMParameterSpec(TAG_BITS, nonce));
      cipher.updateAAD(kid.getBytes(java.nio.charset.StandardCharsets.UTF_8));
      byte[] sealed = cipher.doFinal(plain);
      byte[] out = Arrays.copyOf(nonce, NONCE_BYTES + sealed.length);
      System.arraycopy(sealed, 0, out, NONCE_BYTES, sealed.length);
      return Base64.getEncoder().encodeToString(out);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("Could not encrypt a signing key", e);
    }
  }

  byte[] decrypt(String stored, String kid) {
    try {
      byte[] in = Base64.getDecoder().decode(stored);
      if (in.length <= NONCE_BYTES) {
        throw new IllegalStateException("Stored signing key is malformed");
      }
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.DECRYPT_MODE, kek, new GCMParameterSpec(TAG_BITS, in, 0, NONCE_BYTES));
      cipher.updateAAD(kid.getBytes(java.nio.charset.StandardCharsets.UTF_8));
      return cipher.doFinal(in, NONCE_BYTES, in.length - NONCE_BYTES);
    } catch (GeneralSecurityException | IllegalArgumentException e) {
      throw new IllegalStateException("Stored signing key could not be decrypted");
    }
  }
}
