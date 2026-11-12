package com.aerotrace.identity.keys;

import java.security.PrivateKey;
import java.security.PublicKey;

/** The key tokens are signed with. The private key stays out of {@link #toString()}. */
public record SigningKey(String kid, String algorithm, PublicKey publicKey, PrivateKey privateKey) {

  @Override
  public String toString() {
    return "SigningKey[kid=" + kid + ", algorithm=" + algorithm + "]";
  }
}
