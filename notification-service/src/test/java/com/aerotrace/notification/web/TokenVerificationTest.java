package com.aerotrace.notification.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aerotrace.common.security.JwtClaims;
import com.aerotrace.common.security.PemKeyUtils;
import com.aerotrace.notification.base.AbstractNotificationTest;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Real signed tokens, checked by the real decoder (the other API tests use a stand-in that skips
 * it): the identity-service key pair signs, and a token is refused when it is expired, meant for
 * another audience or issuer, signed with another key, tampered with or unsigned.
 */
@SpringBootTest
@AutoConfigureMockMvc
class TokenVerificationTest extends AbstractNotificationTest {

  private static final PrivateKey IDENTITY_KEY =
      PemKeyUtils.readPrivateKey(null, "classpath:test-keys/private.pem");
  private static final String NOTIFICATIONS = "/api/v1/notifications";

  @Autowired MockMvc mockMvc;
  @MockitoBean KafkaTemplate<String, String> kafka;

  private static String token(
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

  private static String valid(String role) throws JOSEException {
    return token(
        IDENTITY_KEY,
        JwtClaims.ISSUER,
        "aerotrace-api",
        Instant.now().plusSeconds(600),
        List.of(role));
  }

  private void expectStatus(String bearer, int expected) throws Exception {
    mockMvc
        .perform(get(NOTIFICATIONS).header("Authorization", "Bearer " + bearer))
        .andExpect(status().is(expected));
  }

  @Test
  @DisplayName("AT-16 a token from the identity-service is accepted for every role")
  void validTokenIsAccepted() throws Exception {
    for (String role : List.of("PASSENGER", "AGENT", "DISPATCHER", "ADMIN")) {
      expectStatus(valid(role), 200);
    }
  }

  @Test
  @DisplayName("AT-16 an expired token is a 401 that does not say why")
  void expiredTokenIsRefused() throws Exception {
    String expired =
        token(
            IDENTITY_KEY,
            JwtClaims.ISSUER,
            "aerotrace-api",
            Instant.now().minusSeconds(600),
            List.of("AGENT"));

    mockMvc
        .perform(get(NOTIFICATIONS).header("Authorization", "Bearer " + expired))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
  }

  @Test
  @DisplayName("AT-16 a token for another audience or issuer is refused")
  void wrongAudienceOrIssuerIsRefused() throws Exception {
    Instant later = Instant.now().plusSeconds(600);

    expectStatus(token(IDENTITY_KEY, JwtClaims.ISSUER, "other-api", later, List.of("AGENT")), 401);
    expectStatus(
        token(IDENTITY_KEY, "someone-else", "aerotrace-api", later, List.of("AGENT")), 401);
  }

  @Test
  @DisplayName("AT-16 a token signed with another key, tampered with or not a token is refused")
  void forgedTokensAreRefused() throws Exception {
    KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(2048);
    KeyPair other = generator.generateKeyPair();

    expectStatus(
        token(
            other.getPrivate(),
            JwtClaims.ISSUER,
            "aerotrace-api",
            Instant.now().plusSeconds(600),
            List.of("ADMIN")),
        401);

    String[] parts = valid("PASSENGER").split("\\.");
    String forgedPayload =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(
                ("{\"sub\":\"x\",\"iss\":\""
                        + JwtClaims.ISSUER
                        + "\",\"aud\":\"aerotrace-api\",\"roles\":[\"ADMIN\"],\"exp\":"
                        + (Instant.now().getEpochSecond() + 600)
                        + "}")
                    .getBytes(StandardCharsets.UTF_8));
    expectStatus(parts[0] + "." + forgedPayload + "." + parts[2], 401);
    expectStatus("not-a-token", 401);
  }

  @Test
  @DisplayName("AT-16 an unsigned token (alg none) is refused")
  void unsignedTokenIsRefused() throws Exception {
    String header =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString("{\"alg\":\"none\"}".getBytes(StandardCharsets.UTF_8));
    String payload =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(
                ("{\"iss\":\""
                        + JwtClaims.ISSUER
                        + "\",\"aud\":\"aerotrace-api\",\"roles\":[\"ADMIN\"],\"exp\":"
                        + (Instant.now().getEpochSecond() + 600)
                        + "}")
                    .getBytes(StandardCharsets.UTF_8));

    expectStatus(header + "." + payload + ".", 401);
  }

  @Test
  @DisplayName("AT-16 a valid token whose role is unknown grants nothing: 403")
  void unknownRoleIsForbidden() throws Exception {
    expectStatus(valid("SUPERUSER"), 403);
  }
}
