package com.aerotrace.identity.security.jwt;

import com.aerotrace.identity.constant.JwtConstants;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Access token and signing key settings.
 *
 * <p>The key pair is read from PEM, either inline or from a location (a Spring resource such as
 * {@code classpath:keys/private.pem}, or a file path). Inline text wins over a location. When
 * nothing is configured the development pair that ships inside the service is used. The issuer of
 * the tokens is fixed ({@code JwtClaims.ISSUER}), so it is not a setting.
 *
 * @param audience the {@code aud} claim resource servers require
 * @param ttl access token lifetime
 * @param clockSkew the clock difference tolerated when checking {@code exp}
 * @param privateKey inline PKCS#8 private key (PEM), or empty
 * @param privateKeyLocation where to read the private key from when none is given inline
 * @param publicKey inline public key (PEM), or empty
 * @param publicKeyLocation where to read the public key from when none is given inline
 */
@ConfigurationProperties(JwtConstants.PROPERTY_PREFIX)
public record JwtProperties(
    @DefaultValue(JwtConstants.DEFAULT_AUDIENCE) String audience,
    @DefaultValue(JwtConstants.DEFAULT_TTL) Duration ttl,
    @DefaultValue(JwtConstants.DEFAULT_CLOCK_SKEW) Duration clockSkew,
    String privateKey,
    String privateKeyLocation,
    String publicKey,
    String publicKeyLocation) {

  public JwtProperties {
    if (isBlank(privateKey) && isBlank(privateKeyLocation)) {
      privateKeyLocation = JwtConstants.DEFAULT_PRIVATE_KEY_LOCATION;
    }
    if (isBlank(publicKey) && isBlank(publicKeyLocation)) {
      publicKeyLocation = JwtConstants.DEFAULT_PUBLIC_KEY_LOCATION;
    }
  }

  /** True when the key pair is the development one that ships with the service. */
  public boolean usesBundledKeys() {
    return isBlank(privateKey)
        && JwtConstants.DEFAULT_PRIVATE_KEY_LOCATION.equals(privateKeyLocation);
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }

  /** Never prints key material. */
  @Override
  public String toString() {
    return "JwtProperties[audience=" + audience + ", ttl=" + ttl + "]";
  }
}
