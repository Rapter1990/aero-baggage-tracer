package com.aerotrace.gateway.support;

import com.aerotrace.common.security.JwtClaims;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;

/**
 * Stands in for identity-service: one RSA key pair that the gateway under test trusts (its public
 * key is handed to the gateway as inline PEM), another that it does not, and tokens signed with
 * either.
 */
public final class TestTokens {

  public static final String ISSUER = JwtClaims.ISSUER;
  public static final String AUDIENCE = "aerotrace-api";

  private static final RSAKey TRUSTED = generate();

  private TestTokens() {}

  /** The public key of the trusted pair as PEM, for {@code aerotrace.gateway.public-key}. */
  public static String publicKeyPem() {
    try {
      byte[] der = TRUSTED.toRSAPublicKey().getEncoded();
      String body =
          Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.UTF_8)).encodeToString(der);
      return "-----BEGIN PUBLIC KEY-----\n" + body + "\n-----END PUBLIC KEY-----\n";
    } catch (JOSEException e) {
      throw new IllegalStateException(e);
    }
  }

  /** The key pair whose public half the gateway trusts, like identity-service's signing key. */
  public static RSAKey trusted() {
    return TRUSTED;
  }

  /** A key pair the gateway has never heard of, to model a forged or foreign signature. */
  public static RSAKey untrusted() {
    return generate();
  }

  private static RSAKey generate() {
    try {
      return new RSAKeyGenerator(2048).generate();
    } catch (JOSEException e) {
      throw new IllegalStateException(e);
    }
  }

  public static JWTClaimsSet.Builder claims(String subject, List<String> roles) {
    Instant now = Instant.now();
    return new JWTClaimsSet.Builder()
        .issuer(ISSUER)
        .audience(AUDIENCE)
        .subject(subject)
        .claim("roles", roles)
        .issueTime(Date.from(now))
        .expirationTime(Date.from(now.plusSeconds(300)));
  }

  public static String sign(RSAKey key, JWTClaimsSet claims) {
    try {
      SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).build(), claims);
      jwt.sign(new RSASSASigner(key));
      return jwt.serialize();
    } catch (JOSEException e) {
      throw new IllegalStateException(e);
    }
  }

  public static String unsigned(JWTClaimsSet claims) {
    return new PlainJWT(claims).serialize();
  }
}
