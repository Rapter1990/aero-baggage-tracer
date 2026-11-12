package com.aerotrace.identity.keys;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSSigner;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;

/** The asymmetric signature algorithms supported, built on plain {@code java.security} keys. */
enum SigningAlgorithm {
  RS256("RSA", JWSAlgorithm.RS256),
  ES256("EC", JWSAlgorithm.ES256);

  private final String keyFactoryName;
  private final JWSAlgorithm jws;

  SigningAlgorithm(String keyFactoryName, JWSAlgorithm jws) {
    this.keyFactoryName = keyFactoryName;
    this.jws = jws;
  }

  static SigningAlgorithm parse(String name) {
    for (SigningAlgorithm algorithm : values()) {
      if (algorithm.name().equals(name)) {
        return algorithm;
      }
    }
    throw new IllegalStateException("aerotrace.identity.keys.algorithm must be RS256 or ES256");
  }

  JWSAlgorithm jws() {
    return jws;
  }

  KeyPair generate() {
    try {
      KeyPairGenerator generator = KeyPairGenerator.getInstance(keyFactoryName);
      if (this == RS256) {
        generator.initialize(2048);
      } else {
        generator.initialize(new ECGenParameterSpec("secp256r1"));
      }
      return generator.generateKeyPair();
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("Could not generate a signing key", e);
    }
  }

  PublicKey decodePublic(byte[] x509) {
    try {
      return KeyFactory.getInstance(keyFactoryName).generatePublic(new X509EncodedKeySpec(x509));
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("Stored public key is unreadable", e);
    }
  }

  PrivateKey decodePrivate(byte[] pkcs8) {
    try {
      return KeyFactory.getInstance(keyFactoryName).generatePrivate(new PKCS8EncodedKeySpec(pkcs8));
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("Stored private key is unreadable", e);
    }
  }

  /** The public JWK for a key, without a key id. */
  JWK toJwk(PublicKey publicKey, String kid) {
    if (this == RS256) {
      RSAKey.Builder builder = new RSAKey.Builder((RSAPublicKey) publicKey);
      return builder.keyUse(KeyUse.SIGNATURE).algorithm(jws).keyID(kid).build();
    }
    ECKey.Builder builder = new ECKey.Builder(Curve.P_256, (ECPublicKey) publicKey);
    return builder.keyUse(KeyUse.SIGNATURE).algorithm(jws).keyID(kid).build();
  }

  JWSSigner signer(PrivateKey privateKey) {
    try {
      return this == RS256
          ? new RSASSASigner(privateKey)
          : new ECDSASigner((ECPrivateKey) privateKey);
    } catch (JOSEException e) {
      throw new IllegalStateException("Could not create a signer", e);
    }
  }
}
