package com.aerotrace.identity.keys;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import org.junit.jupiter.api.Test;

/** Private keys are encrypted at rest with AES-GCM, bound to their key id. */
class KeyCipherTest {

  private static String newKek() {
    byte[] key = new byte[32];
    new SecureRandom().nextBytes(key);
    return Base64.getEncoder().encodeToString(key);
  }

  private static final byte[] SECRET = "pkcs8-private-key-bytes".getBytes(StandardCharsets.UTF_8);

  @Test
  void decryptsWhatItEncrypted() {
    KeyCipher cipher = KeyCipher.fromBase64(newKek());

    String stored = cipher.encrypt(SECRET, "kid-1");

    assertThat(cipher.decrypt(stored, "kid-1")).isEqualTo(SECRET);
  }

  @Test
  void theStoredFormNeverContainsThePlaintext() {
    String stored = KeyCipher.fromBase64(newKek()).encrypt(SECRET, "kid-1");

    assertThat(stored).doesNotContain("pkcs8");
    assertThat(new String(Base64.getDecoder().decode(stored), StandardCharsets.ISO_8859_1))
        .doesNotContain("pkcs8-private");
  }

  @Test
  void everyEncryptionUsesAFreshNonce() {
    KeyCipher cipher = KeyCipher.fromBase64(newKek());

    assertThat(cipher.encrypt(SECRET, "kid-1")).isNotEqualTo(cipher.encrypt(SECRET, "kid-1"));
  }

  @Test
  void aCiphertextCannotBeMovedToAnotherKeyId() {
    KeyCipher cipher = KeyCipher.fromBase64(newKek());
    String stored = cipher.encrypt(SECRET, "kid-1");

    assertThatThrownBy(() -> cipher.decrypt(stored, "kid-2"))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void aDifferentKeyEncryptionKeyCannotDecrypt() {
    String stored = KeyCipher.fromBase64(newKek()).encrypt(SECRET, "kid-1");

    assertThatThrownBy(() -> KeyCipher.fromBase64(newKek()).decrypt(stored, "kid-1"))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void aTamperedCiphertextIsRejected() {
    KeyCipher cipher = KeyCipher.fromBase64(newKek());
    byte[] raw = Base64.getDecoder().decode(cipher.encrypt(SECRET, "kid-1"));
    raw[raw.length - 1] ^= 1;
    String tampered = Base64.getEncoder().encodeToString(raw);

    assertThatThrownBy(() -> cipher.decrypt(tampered, "kid-1"))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void aTruncatedCiphertextIsRejected() {
    KeyCipher cipher = KeyCipher.fromBase64(newKek());

    assertThatThrownBy(() -> cipher.decrypt("AAAA", "kid-1"))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void refusesAMissingOrWeakKeyEncryptionKeyWithoutEchoingIt() {
    String shortKey = Base64.getEncoder().encodeToString(new byte[16]);

    assertThatThrownBy(() -> KeyCipher.fromBase64(null)).isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> KeyCipher.fromBase64(" ")).isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> KeyCipher.fromBase64(shortKey))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageNotContaining(shortKey);
    assertThatThrownBy(() -> KeyCipher.fromBase64("not base64 !!"))
        .isInstanceOf(IllegalStateException.class);
  }
}
