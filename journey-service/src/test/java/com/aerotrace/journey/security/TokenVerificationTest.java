package com.aerotrace.journey.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aerotrace.common.security.JwtClaims;
import com.aerotrace.common.security.PemKeyUtils;
import com.aerotrace.journey.base.AbstractApiTest;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Real signed tokens, checked by the real decoder: the identity-service key pair signs, and a token
 * is refused when it is expired, meant for another audience or issuer, signed with another key or
 * tampered with.
 */
class TokenVerificationTest extends AbstractApiTest {

  private static final PrivateKey IDENTITY_KEY =
      PemKeyUtils.readPrivateKey(null, "classpath:test-keys/private.pem");

  private String token(
      PrivateKey key, String issuer, String audience, Instant expires, List<String> roles)
      throws JOSEException {
    JWTClaimsSet claims =
        new JWTClaimsSet.Builder()
            .subject("test-user")
            .issuer(issuer)
            .audience(audience)
            .claim(JwtClaims.ROLES, roles)
            .issueTime(Date.from(Instant.now().minusSeconds(30)))
            .expirationTime(Date.from(expires))
            .build();
    SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims);
    jwt.sign(new RSASSASigner(key));
    return jwt.serialize();
  }

  private String valid() throws JOSEException {
    return token(
        IDENTITY_KEY,
        JwtClaims.ISSUER,
        "aerotrace-api",
        Instant.now().plusSeconds(600),
        List.of("AGENT"));
  }

  private void expectStatus(String bearer, int expected) throws Exception {
    mockMvc
        .perform(
            get("/api/v1/bags/{bagTag}/journey", "0000000001")
                .header("Authorization", "Bearer " + bearer))
        .andExpect(status().is(expected));
  }

  @Test
  void aTokenFromTheIdentityServiceIsAccepted() throws Exception {
    // 404: the token passed authentication and authorization; the bag simply does not exist.
    expectStatus(valid(), 404);
  }

  @Test
  void anExpiredTokenIsRefusedWithoutSayingWhy() throws Exception {
    String expired =
        token(
            IDENTITY_KEY,
            JwtClaims.ISSUER,
            "aerotrace-api",
            Instant.now().minusSeconds(600),
            List.of("AGENT"));

    mockMvc
        .perform(
            get("/api/v1/bags/{bagTag}/journey", "0000000001")
                .header("Authorization", "Bearer " + expired))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
        .andExpect(jsonPath("$.message").value("Authentication required"));
  }

  @Test
  void aTokenForAnotherAudienceOrIssuerIsRefused() throws Exception {
    Instant later = Instant.now().plusSeconds(600);

    expectStatus(token(IDENTITY_KEY, JwtClaims.ISSUER, "other-api", later, List.of("AGENT")), 401);
    expectStatus(
        token(IDENTITY_KEY, "someone-else", "aerotrace-api", later, List.of("AGENT")), 401);
  }

  @Test
  void aTokenSignedWithAnotherKeyOrTamperedWithIsRefused() throws Exception {
    KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(2048);
    KeyPair other = generator.generateKeyPair();

    expectStatus(
        token(
            other.getPrivate(),
            JwtClaims.ISSUER,
            "aerotrace-api",
            Instant.now().plusSeconds(600),
            List.of("AGENT")),
        401);

    String[] parts = valid().split("\\.");
    // Another payload under the original signature.
    String forgedPayload =
        java.util.Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(
                ("{\"sub\":\"x\",\"iss\":\""
                        + JwtClaims.ISSUER
                        + "\",\"aud\":\"aerotrace-api\","
                        + "\"roles\":[\"ADMIN\"],\"exp\":"
                        + (Instant.now().getEpochSecond() + 600)
                        + "}")
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    expectStatus(parts[0] + "." + forgedPayload + "." + parts[2], 401);
    expectStatus("not-a-token", 401);
  }

  @Test
  void anUnsignedTokenIsRefused() throws Exception {
    String header =
        java.util.Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString("{\"alg\":\"none\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    String payload =
        java.util.Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(
                ("{\"iss\":\""
                        + JwtClaims.ISSUER
                        + "\",\"aud\":\"aerotrace-api\",\"roles\":[\"ADMIN\"],"
                        + "\"exp\":"
                        + (Instant.now().getEpochSecond() + 600)
                        + "}")
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));

    expectStatus(header + "." + payload + ".", 401);
  }

  @Test
  void aTokenWithAnUnknownRoleGrantsNothing() throws Exception {
    String token =
        token(
            IDENTITY_KEY,
            JwtClaims.ISSUER,
            "aerotrace-api",
            Instant.now().plusSeconds(600),
            List.of("SUPERUSER"));

    expectStatus(token, 403);
  }
}
