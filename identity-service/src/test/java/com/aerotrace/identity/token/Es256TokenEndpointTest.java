package com.aerotrace.identity.token;

import org.springframework.test.context.TestPropertySource;

@TestPropertySource(properties = "aerotrace.identity.keys.algorithm=ES256")
class Es256TokenEndpointTest extends AbstractTokenEndpointTest {

  @Override
  String algorithm() {
    return "ES256";
  }
}
