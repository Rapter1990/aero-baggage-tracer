package com.aerotrace.identity.token;

class Rs256TokenEndpointTest extends AbstractTokenEndpointTest {

  @Override
  String algorithm() {
    return "RS256";
  }
}
