package com.aerotrace.identity.security.jwt;

import com.aerotrace.common.security.JwtClaims;
import com.aerotrace.identity.constant.JwtConstants;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtBuilder;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.MalformedJwtException;
import java.security.GeneralSecurityException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Signs and verifies the access tokens with the RSA key pair read from PEM.
 *
 * <p>A token is an RS256 JWT carrying the issuer, the audience, the subject, the roles, the
 * passenger reference (passengers only), a unique id and the issue and expiry times. Its header
 * names the key ({@code kid}, the RFC 7638 thumbprint of the public key) so verifiers that read the
 * published key set can find it.
 *
 * <p>Verification checks the signature, the issuer, the audience and the expiry (a token without an
 * expiry is refused), and only accepts the configured RSA public key: an unsigned token, or one
 * signed with a shared secret, is rejected.
 */
@Slf4j
@Service
public class JwtTokenService {

  private final JwtProperties properties;
  private final Clock clock;
  private final PrivateKey privateKey;
  private final PublicKey publicKey;
  private final String keyId;
  private final String jwksJson;

  /**
   * Reads the key pair and checks it.
   *
   * @param properties token and key settings
   * @param clock the time used to stamp and check tokens
   * @throws IllegalStateException if a setting is missing, a key cannot be read or parsed, or the
   *     two keys are not a pair
   */
  public JwtTokenService(JwtProperties properties, Clock clock) {
    if (properties.issuer() == null || properties.issuer().isBlank()) {
      throw new IllegalStateException(JwtConstants.PROPERTY_PREFIX + ".issuer must be set");
    }
    if (properties.ttl().isNegative() || properties.ttl().isZero()) {
      throw new IllegalStateException(JwtConstants.PROPERTY_PREFIX + ".ttl must be positive");
    }
    this.properties = properties;
    this.clock = clock;
    this.publicKey =
        PemKeyUtils.readPublicKey(properties.publicKey(), properties.publicKeyLocation());
    this.privateKey =
        PemKeyUtils.readPrivateKey(properties.privateKey(), properties.privateKeyLocation());
    requireMatchingPair(privateKey, publicKey);
    RSAKey jwk = describe(publicKey, null);
    this.keyId = thumbprint(jwk);
    this.jwksJson = new JWKSet(describe(publicKey, keyId)).toString();
    if (properties.usesBundledKeys()) {
      log.warn(
          "The development key pair that ships with identity-service is in use. Its private key is"
              + " in the repository, so anyone can sign tokens that this platform accepts. Set"
              + " IDENTITY_JWT_PRIVATE_KEY_LOCATION and IDENTITY_JWT_PUBLIC_KEY_LOCATION to your"
              + " own key pair before relying on these tokens.");
    }
  }

  /**
   * Generates a signed JWT access token for the given user.
   *
   * @param subject the subject: the user's id
   * @param roles granted roles to store in the token claims
   * @param passengerRef the passenger's pseudonymous reference, or {@code null} for staff
   * @return signed JWT token
   */
  public String generateToken(String subject, List<String> roles, String passengerRef) {
    Instant now = clock.instant();
    JwtBuilder builder =
        Jwts.builder()
            .header()
            .keyId(keyId)
            .type(JwtConstants.HEADER_TYPE)
            .and()
            .issuer(properties.issuer())
            .subject(subject)
            .audience()
            .add(properties.audience())
            .and()
            .id(UUID.randomUUID().toString())
            .claim(JwtClaims.ROLES, roles)
            .issuedAt(Date.from(now))
            .expiration(Date.from(now.plus(properties.ttl())));
    if (passengerRef != null) {
      builder.claim(JwtClaims.PASSENGER_REF, passengerRef);
    }
    return builder.signWith(privateKey, Jwts.SIG.RS256).compact();
  }

  /**
   * Parses and validates JWT claims.
   *
   * <p>The signature is verified with the public key, and the issuer, the audience and the expiry
   * (with the configured clock skew) are required to match.
   *
   * @param token JWT token to parse
   * @return parsed JWT claims
   * @throws io.jsonwebtoken.JwtException if the token is expired, malformed, unsupported, signed
   *     with another key, or meant for another issuer or audience
   */
  public Claims parseClaims(String token) {
    Claims claims =
        Jwts.parser()
            .verifyWith(publicKey)
            .requireIssuer(properties.issuer())
            .requireAudience(properties.audience())
            .clockSkewSeconds(properties.clockSkew().toSeconds())
            .clock(() -> Date.from(clock.instant()))
            .build()
            .parseSignedClaims(token)
            .getPayload();
    if (claims.getExpiration() == null) {
      throw new MalformedJwtException("The token has no expiry");
    }
    return claims;
  }

  /** The id of the signing key, as it appears in the token header and the published key set. */
  public String keyId() {
    return keyId;
  }

  /** The published key set (JWKS) as JSON: the public key only. */
  public String jwksJson() {
    return jwksJson;
  }

  /** How long a token is valid. */
  public Duration ttl() {
    return properties.ttl();
  }

  private static RSAKey describe(PublicKey key, String kid) {
    RSAKey.Builder builder =
        new RSAKey.Builder((RSAPublicKey) key)
            .keyUse(KeyUse.SIGNATURE)
            .algorithm(JWSAlgorithm.RS256);
    if (kid != null) {
      builder.keyID(kid);
    }
    return builder.build();
  }

  private static String thumbprint(RSAKey key) {
    try {
      return key.computeThumbprint().toString();
    } catch (JOSEException e) {
      throw new IllegalStateException("Could not derive a key id from the public key", e);
    }
  }

  /** A mismatched pair would sign tokens nobody can verify; stop at start-up instead. */
  private static void requireMatchingPair(PrivateKey signing, PublicKey verifying) {
    try {
      byte[] probe = "key-pair-check".getBytes(java.nio.charset.StandardCharsets.UTF_8);
      Signature signer = Signature.getInstance("SHA256withRSA");
      signer.initSign(signing);
      signer.update(probe);
      byte[] signature = signer.sign();
      Signature verifier = Signature.getInstance("SHA256withRSA");
      verifier.initVerify(verifying);
      verifier.update(probe);
      if (!verifier.verify(signature)) {
        throw new IllegalStateException("The configured JWT private and public keys do not match");
      }
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("The configured JWT private and public keys do not match", e);
    }
  }
}
