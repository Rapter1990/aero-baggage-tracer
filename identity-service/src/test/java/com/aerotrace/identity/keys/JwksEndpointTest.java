package com.aerotrace.identity.keys;

import static org.assertj.core.api.Assertions.assertThat;

import com.aerotrace.identity.support.IdentityTestBase;
import com.nimbusds.jose.jwk.JWKSet;
import java.net.http.HttpResponse;
import java.text.ParseException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** The public keys resource servers use to verify tokens. */
class JwksEndpointTest extends IdentityTestBase {

  @Autowired private SigningKeyService keys;

  @Test
  void servesTheSetWithoutAnyAuthentication() throws ParseException {
    HttpResponse<String> response = get("/.well-known/jwks.json");

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.headers().firstValue("Content-Type").orElse(""))
        .startsWith("application/json");
    JWKSet set = JWKSet.parse(response.body());
    assertThat(set.getKeys()).isNotEmpty();
    assertThat(set.getKeyByKeyId(keys.signingKey().kid())).isNotNull();
  }

  @Test
  void neverExposesPrivateKeyMaterial() throws ParseException {
    String body = get("/.well-known/jwks.json").body();

    assertThat(JWKSet.parse(body).getKeys()).noneMatch(key -> key.isPrivate());
    assertThat(body).doesNotContain("\"d\"", "\"p\"", "\"q\"", "\"dp\"", "\"dq\"", "\"qi\"");
  }

  @Test
  void letsCachesKeepItBrieflySoTheGatewayIsNotHammered() {
    String cacheControl =
        get("/.well-known/jwks.json").headers().firstValue("Cache-Control").orElse("");

    assertThat(cacheControl).contains("public").contains("max-age=60");
  }
}
