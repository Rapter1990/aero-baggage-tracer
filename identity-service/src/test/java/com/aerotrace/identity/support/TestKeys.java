package com.aerotrace.identity.support;

import io.jsonwebtoken.Jwts;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.UUID;

/** RSA keys and tokens for tests: the bundled development pair, other pairs, and any claims. */
public final class TestKeys {

  public static final String ISSUER = "http://identity.test";
  public static final String AUDIENCE = "aerotrace-api";

  private TestKeys() {}

  /** The development private key that ships inside the service. */
  public static PrivateKey bundledPrivateKey() {
    try {
      return KeyFactory.getInstance("RSA")
          .generatePrivate(new PKCS8EncodedKeySpec(der("keys/private.pem")));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  /** The development public key that ships inside the service. */
  public static PublicKey bundledPublicKey() {
    try {
      return KeyFactory.getInstance("RSA")
          .generatePublic(new X509EncodedKeySpec(der("keys/public.pem")));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  public static String bundledPrivatePem() {
    return resource("keys/private.pem");
  }

  public static String bundledPublicPem() {
    return resource("keys/public.pem");
  }

  /** A fresh pair that the service has never heard of. */
  public static KeyPair generate() {
    try {
      KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
      generator.initialize(2048);
      return generator.generateKeyPair();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  public static String privatePem(PrivateKey key) {
    return pem("PRIVATE KEY", key.getEncoded());
  }

  public static String publicPem(PublicKey key) {
    return pem("PUBLIC KEY", key.getEncoded());
  }

  /**
   * A signed token with exactly the claims given, so tests can build the tokens a careless or
   * hostile issuer might produce: expired, for another audience, with unknown roles...
   */
  public static String token(
      PrivateKey signer,
      String kid,
      String issuer,
      String audience,
      List<String> roles,
      Instant expires) {
    var builder =
        Jwts.builder()
            .issuer(issuer)
            .subject(UUID.randomUUID().toString())
            .id(UUID.randomUUID().toString())
            .claim("roles", roles)
            .issuedAt(new Date());
    if (audience != null) {
      builder.audience().add(audience);
    }
    if (kid != null) {
      builder.header().keyId(kid).and();
    }
    if (expires != null) {
      builder.expiration(Date.from(expires));
    }
    return builder.signWith(signer, Jwts.SIG.RS256).compact();
  }

  private static String pem(String type, byte[] der) {
    String body =
        Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.UTF_8)).encodeToString(der);
    return "-----BEGIN " + type + "-----\n" + body + "\n-----END " + type + "-----\n";
  }

  private static byte[] der(String resource) {
    String pem =
        resource(resource).replaceAll("-----(BEGIN|END) [A-Z ]+-----", "").replaceAll("\\s", "");
    return Base64.getDecoder().decode(pem);
  }

  private static String resource(String name) {
    try (InputStream in = TestKeys.class.getClassLoader().getResourceAsStream(name)) {
      if (in == null) {
        throw new IllegalStateException("Missing test resource " + name);
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }
}
