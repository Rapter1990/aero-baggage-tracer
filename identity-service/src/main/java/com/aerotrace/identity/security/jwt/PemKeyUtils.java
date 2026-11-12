package com.aerotrace.identity.security.jwt;

import com.aerotrace.identity.constant.JwtConstants;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;

/**
 * Utility class for reading and parsing RSA public/private keys from PEM format.
 *
 * <p>Supported formats:
 *
 * <ul>
 *   <li>{@code -----BEGIN PUBLIC KEY-----}
 *   <li>{@code -----BEGIN PRIVATE KEY-----}
 * </ul>
 *
 * <p>Private keys must be in PKCS#8 format.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
final class PemKeyUtils {

  /**
   * Reads and parses an RSA public key.
   *
   * <p>The public key can be provided either as inline PEM text or as a file/classpath location.
   *
   * @param inlinePem inline public key in PEM format
   * @param location public key location, such as {@code classpath:keys/public.pem} or a filesystem
   *     path
   * @return parsed RSA public key
   * @throws IllegalStateException if the key cannot be read or parsed
   */
  static PublicKey readPublicKey(String inlinePem, String location) {
    String pem = readPem(inlinePem, location, "public key");
    byte[] der = decodePem(pem, JwtConstants.PEM_PUBLIC_KEY);
    try {
      return KeyFactory.getInstance(JwtConstants.KEY_ALGORITHM)
          .generatePublic(new X509EncodedKeySpec(der));
    } catch (Exception exception) {
      throw new IllegalStateException("Cannot parse RSA public key", exception);
    }
  }

  /**
   * Reads and parses an RSA private key.
   *
   * <p>The private key can be provided either as inline PEM text or as a file/classpath location.
   *
   * @param inlinePem inline private key in PEM format
   * @param location private key location, such as {@code classpath:keys/private.pem} or a
   *     filesystem path
   * @return parsed RSA private key
   * @throws IllegalStateException if the key cannot be read or parsed
   */
  static PrivateKey readPrivateKey(String inlinePem, String location) {
    String pem = readPem(inlinePem, location, "private key");
    byte[] der = decodePem(pem, JwtConstants.PEM_PRIVATE_KEY);
    try {
      return KeyFactory.getInstance(JwtConstants.KEY_ALGORITHM)
          .generatePrivate(new PKCS8EncodedKeySpec(der));
    } catch (Exception exception) {
      throw new IllegalStateException(
          "Cannot parse RSA private key. Use PKCS#8 PEM format: -----BEGIN PRIVATE KEY-----",
          exception);
    }
  }

  /**
   * Reads PEM content from inline text or from a configured location.
   *
   * <p>If inline PEM content exists, it has priority. Otherwise, the method tries to read the key
   * from a Spring resource location first. If the resource does not exist, it tries to read the
   * value as a normal filesystem path.
   *
   * @param inlinePem inline PEM content
   * @param location classpath or filesystem location
   * @param label key label used in error messages
   * @return PEM content as text
   * @throws IllegalStateException if PEM content cannot be found or read
   */
  private static String readPem(String inlinePem, String location, String label) {
    if (hasText(inlinePem)) {
      return inlinePem;
    }
    if (!hasText(location)) {
      String name = label.replace(' ', '-');
      throw new IllegalStateException(
          "Missing JWT "
              + label
              + ". Configure "
              + JwtConstants.PROPERTY_PREFIX
              + "."
              + name
              + " or "
              + JwtConstants.PROPERTY_PREFIX
              + "."
              + name
              + "-location");
    }
    try {
      Resource resource = new DefaultResourceLoader().getResource(location);
      if (resource.exists()) {
        // Closed after reading: an open handle keeps the key file locked (on Windows, undeletable).
        try (InputStream in = resource.getInputStream()) {
          return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
      }
      return Files.readString(Path.of(location), StandardCharsets.UTF_8);
    } catch (IOException exception) {
      throw new IllegalStateException(
          "Cannot read JWT " + label + " from location: " + location, exception);
    }
  }

  /**
   * Decodes PEM text into DER byte array.
   *
   * <p>Header, footer, and whitespace characters are removed before Base64 decoding.
   *
   * @param pem PEM content
   * @param type PEM key type, such as {@code PUBLIC KEY} or {@code PRIVATE KEY}
   * @return decoded DER bytes
   * @throws IllegalArgumentException if the PEM content is not valid Base64
   */
  private static byte[] decodePem(String pem, String type) {
    String normalized =
        pem.replace(JwtConstants.PEM_BEGIN_PREFIX + type + JwtConstants.PEM_SUFFIX, "")
            .replace(JwtConstants.PEM_END_PREFIX + type + JwtConstants.PEM_SUFFIX, "")
            .replaceAll("\\s", "");
    return Base64.getDecoder().decode(normalized);
  }

  /**
   * Checks whether the given value contains non-blank text.
   *
   * @param value value to check
   * @return {@code true} if value is not {@code null} and not blank
   */
  private static boolean hasText(String value) {
    return value != null && !value.isBlank();
  }
}
