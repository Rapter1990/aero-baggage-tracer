package com.aerotrace.common.security;

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
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;

/**
 * Reading RSA public and private keys from PEM. Shared by identity-service (signs tokens with the
 * private key) and the api-gateway (verifies them with the public key).
 *
 * <p>Supported formats: {@code -----BEGIN PUBLIC KEY-----} (X.509) and {@code -----BEGIN PRIVATE
 * KEY-----} (PKCS#8). The key can be given as inline PEM text, which wins, or as a location: a
 * Spring resource such as {@code classpath:keys/public.pem} or {@code file:/run/secrets/x.pem}, or
 * a plain file path.
 */
public final class PemKeyUtils {

  private static final String KEY_ALGORITHM = "RSA";
  private static final String PUBLIC_KEY = "PUBLIC KEY";
  private static final String PRIVATE_KEY = "PRIVATE KEY";
  private static final String BEGIN_PREFIX = "-----BEGIN ";
  private static final String END_PREFIX = "-----END ";
  private static final String SUFFIX = "-----";

  private PemKeyUtils() {}

  /**
   * Reads and parses an RSA public key.
   *
   * @param inlinePem inline public key in PEM format, or empty
   * @param location public key location, used when there is no inline text
   * @return the parsed key
   * @throws IllegalStateException if the key is missing, cannot be read or cannot be parsed
   */
  public static PublicKey readPublicKey(String inlinePem, String location) {
    String pem = readPem(inlinePem, location, "public key");
    byte[] der = decodePem(pem, PUBLIC_KEY);
    try {
      return KeyFactory.getInstance(KEY_ALGORITHM).generatePublic(new X509EncodedKeySpec(der));
    } catch (Exception exception) {
      throw new IllegalStateException("Cannot parse RSA public key", exception);
    }
  }

  /**
   * Reads and parses an RSA private key in PKCS#8 format.
   *
   * @param inlinePem inline private key in PEM format, or empty
   * @param location private key location, used when there is no inline text
   * @return the parsed key
   * @throws IllegalStateException if the key is missing, cannot be read or cannot be parsed
   */
  public static PrivateKey readPrivateKey(String inlinePem, String location) {
    String pem = readPem(inlinePem, location, "private key");
    byte[] der = decodePem(pem, PRIVATE_KEY);
    try {
      return KeyFactory.getInstance(KEY_ALGORITHM).generatePrivate(new PKCS8EncodedKeySpec(der));
    } catch (Exception exception) {
      throw new IllegalStateException(
          "Cannot parse RSA private key. Use PKCS#8 PEM format: -----BEGIN PRIVATE KEY-----",
          exception);
    }
  }

  private static String readPem(String inlinePem, String location, String label) {
    if (hasText(inlinePem)) {
      return inlinePem;
    }
    if (!hasText(location)) {
      throw new IllegalStateException(
          "Missing JWT " + label + ": configure the inline PEM text or its location");
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

  private static byte[] decodePem(String pem, String type) {
    String normalized =
        pem.replace(BEGIN_PREFIX + type + SUFFIX, "")
            .replace(END_PREFIX + type + SUFFIX, "")
            .replaceAll("\\s", "");
    return Base64.getDecoder().decode(normalized);
  }

  private static boolean hasText(String value) {
    return value != null && !value.isBlank();
  }
}
