package com.aerotrace.search.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aerotrace.common.security.JwtClaims;
import com.aerotrace.common.security.PemKeyUtils;
import com.aerotrace.search.base.AbstractSearchTest;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.security.PrivateKey;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/** Real signed tokens checked by the real decoder (the other tests use a stand-in). */
@SpringBootTest
@AutoConfigureMockMvc
class TokenVerificationTest extends AbstractSearchTest {

  private static final PrivateKey IDENTITY_KEY =
      PemKeyUtils.readPrivateKey(null, "classpath:test-keys/private.pem");
  private static final String STATUS = "/api/v1/search/index/status";

  @Autowired MockMvc mockMvc;

  private static String token(String audience, Instant expires, List<String> roles)
      throws JOSEException {
    JWTClaimsSet claims =
        new JWTClaimsSet.Builder()
            .subject("test-user")
            .issuer(JwtClaims.ISSUER)
            .audience(audience)
            .claim(JwtClaims.ROLES, roles)
            .issueTime(Date.from(Instant.now().minusSeconds(30)))
            .expirationTime(Date.from(expires))
            .build();
    SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims);
    jwt.sign(new RSASSASigner(IDENTITY_KEY));
    return jwt.serialize();
  }

  @Test
  @DisplayName("AT-16 an expired token is a 401")
  void expiredTokenIsRefused() throws Exception {
    String expired = token("aerotrace-api", Instant.now().minusSeconds(600), List.of("ADMIN"));

    mockMvc
        .perform(get(STATUS).header("Authorization", "Bearer " + expired))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
  }

  @Test
  @DisplayName("AT-16 a token for another audience is a 401")
  void wrongAudienceIsRefused() throws Exception {
    String other = token("other-api", Instant.now().plusSeconds(600), List.of("ADMIN"));

    mockMvc
        .perform(get(STATUS).header("Authorization", "Bearer " + other))
        .andExpect(status().isUnauthorized());
  }

  @Test
  @DisplayName("AT-16 a valid passenger token is a 403, a valid dispatcher token passes")
  void rolesComeFromTheToken() throws Exception {
    Instant later = Instant.now().plusSeconds(600);

    mockMvc
        .perform(
            get(STATUS)
                .header(
                    "Authorization",
                    "Bearer " + token("aerotrace-api", later, List.of("PASSENGER"))))
        .andExpect(status().isForbidden());
    mockMvc
        .perform(
            get(STATUS)
                .header(
                    "Authorization",
                    "Bearer " + token("aerotrace-api", later, List.of("DISPATCHER"))))
        .andExpect(
            result ->
                org.assertj.core.api.Assertions.assertThat(result.getResponse().getStatus())
                    .as("passes security")
                    .isNotIn(401, 403));
  }
}
