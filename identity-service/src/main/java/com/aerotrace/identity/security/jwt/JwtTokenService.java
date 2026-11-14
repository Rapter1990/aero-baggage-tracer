package com.aerotrace.identity.security.jwt;

import com.aerotrace.common.security.JwtClaims;
import com.aerotrace.common.security.PemKeyUtils;
import com.aerotrace.identity.constant.JwtConstants;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtBuilder;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.MalformedJwtException;
import java.security.GeneralSecurityException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
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
 * <p>A token is an RS256 JWT carrying the issuer ({@link JwtClaims#ISSUER}), the audience, the
 * subject, the roles, the passenger reference (passengers only), a unique id and the issue and
 * expiry times. The api-gateway verifies it with the matching public key, which it reads from its
 * own copy of {@code public.pem}.
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

  /**
   * Reads the key pair and checks it.
   *
   * @param properties token and key settings
   * @param clock the time used to stamp and check tokens
   * @throws IllegalStateException if a setting is missing, a key cannot be read or parsed, or the
   *     two keys are not a pair
   */
  public JwtTokenService(JwtProperties properties, Clock clock) {
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
    if (properties.usesBundledKeys()) {
      log.warn(
          "The development key pair that ships with identity-service is in use. Its private key is"
              + " in the repository, so anyone can sign tokens that this platform accepts. Set"
              + " IDENTITY_JWT_PRIVATE_KEY_LOCATION and IDENTITY_JWT_PUBLIC_KEY_LOCATION (and the"
              + " gateway's JWT_PUBLIC_KEY_LOCATION) to your own key pair before relying on these"
              + " tokens.");
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
            .type(JwtConstants.HEADER_TYPE)
            .and()
            .issuer(JwtClaims.ISSUER)
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
            .requireIssuer(JwtClaims.ISSUER)
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

  /**
   * Generates a signed JWT refresh token. It looks like an access token (RS256, issuer, subject,
   * unique id, issue and expiry times) but has the refresh audience and the {@code token_use}
   * claim, and no roles, so it cannot be used as an access token. Whether it is still good is
   * decided by the database (a refresh token is revoked and used once), not by the JWT alone.
   *
   * @param subject the subject: the user's id
   * @param ttl how long the token is valid
   * @return signed JWT token
   */
  public String generateRefreshToken(String subject, Duration ttl) {
    Instant now = clock.instant();
    return Jwts.builder()
        .header()
        .type(JwtConstants.HEADER_TYPE)
        .and()
        .issuer(JwtClaims.ISSUER)
        .subject(subject)
        .audience()
        .add(JwtConstants.REFRESH_AUDIENCE)
        .and()
        .id(UUID.randomUUID().toString())
        .claim(JwtConstants.TOKEN_USE_CLAIM, JwtConstants.TOKEN_USE_REFRESH)
        .issuedAt(Date.from(now))
        .expiration(Date.from(now.plus(ttl)))
        .signWith(privateKey, Jwts.SIG.RS256)
        .compact();
  }

  /**
   * Parses and validates a refresh token: signature, issuer, the refresh audience, the {@code
   * token_use} claim and the expiry.
   *
   * @param token the refresh token
   * @return its claims
   * @throws io.jsonwebtoken.JwtException if it is malformed, expired, signed with another key, or
   *     not a refresh token (an access token is refused here)
   */
  public Claims parseRefreshClaims(String token) {
    Claims claims =
        Jwts.parser()
            .verifyWith(publicKey)
            .requireIssuer(JwtClaims.ISSUER)
            .requireAudience(JwtConstants.REFRESH_AUDIENCE)
            .require(JwtConstants.TOKEN_USE_CLAIM, JwtConstants.TOKEN_USE_REFRESH)
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

  /** How long a token is valid. */
  public Duration ttl() {
    return properties.ttl();
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
