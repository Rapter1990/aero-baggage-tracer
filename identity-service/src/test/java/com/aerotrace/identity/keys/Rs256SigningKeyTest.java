package com.aerotrace.identity.keys;

class Rs256SigningKeyTest extends AbstractSigningKeyTest {

  @Override
  String algorithm() {
    return "RS256";
  }

  @Override
  String keyType() {
    return "RSA";
  }
}
