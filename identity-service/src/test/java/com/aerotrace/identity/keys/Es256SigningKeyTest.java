package com.aerotrace.identity.keys;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.jwk.ECKey;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

@TestPropertySource(properties = "aerotrace.identity.keys.algorithm=ES256")
class Es256SigningKeyTest extends AbstractSigningKeyTest {

  @Override
  String algorithm() {
    return "ES256";
  }

  @Override
  String keyType() {
    return "EC";
  }

  @Test
  void usesTheP256Curve() {
    ECKey key = (ECKey) keys.publishedKeys().getKeys().get(0);

    assertThat(key.getCurve().getName()).isEqualTo("P-256");
  }
}
