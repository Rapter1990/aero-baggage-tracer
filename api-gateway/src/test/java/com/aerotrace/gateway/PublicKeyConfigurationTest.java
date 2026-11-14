package com.aerotrace.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.aerotrace.common.security.PemKeyUtils;
import com.aerotrace.gateway.config.GatewayProperties;
import java.security.interfaces.RSAPublicKey;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Where the gateway gets the public key that verifies the tokens. */
class PublicKeyConfigurationTest {

  private static GatewayProperties properties(String key, String location) {
    return new GatewayProperties("aerotrace-api", key, location, List.of(), null, null, null);
  }

  @Test
  void withNothingConfiguredTheKeyPackagedFromIdentityServiceIsUsed() {
    GatewayProperties properties = properties("", "");

    assertThat(properties.publicKeyLocation()).isEqualTo("classpath:keys/public.pem");
    RSAPublicKey key =
        (RSAPublicKey)
            PemKeyUtils.readPublicKey(properties.publicKey(), properties.publicKeyLocation());
    assertThat(key.getAlgorithm()).isEqualTo("RSA");
    assertThat(key.getModulus().bitLength()).isGreaterThanOrEqualTo(2048);
  }

  @Test
  void aConfiguredLocationOrInlineKeyIsLeftAlone() {
    assertThat(properties("", "file:/run/secrets/public.pem").publicKeyLocation())
        .isEqualTo("file:/run/secrets/public.pem");
    assertThat(properties("-----BEGIN PUBLIC KEY-----", null).publicKeyLocation()).isNull();
  }
}
