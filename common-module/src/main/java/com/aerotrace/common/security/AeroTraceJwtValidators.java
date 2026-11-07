package com.aerotrace.common.security;

import java.util.List;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;

/**
 * Claim validation shared by the gateway and resource services: expiration, issuer, audience.
 * Signature verification is done by each service's {@code JwtDecoder} (JWKS or public key); set
 * this validator on that decoder.
 */
public final class AeroTraceJwtValidators {

  private AeroTraceJwtValidators() {}

  public static OAuth2TokenValidator<Jwt> create(String issuer, String audience) {
    return new DelegatingOAuth2TokenValidator<>(
        new JwtTimestampValidator(),
        new JwtIssuerValidator(issuer),
        new AudienceValidator(audience));
  }

  private record AudienceValidator(String audience) implements OAuth2TokenValidator<Jwt> {

    @Override
    public OAuth2TokenValidatorResult validate(Jwt jwt) {
      List<String> aud = jwt.getAudience();
      if (aud != null && aud.contains(audience)) {
        return OAuth2TokenValidatorResult.success();
      }
      return OAuth2TokenValidatorResult.failure(
          new OAuth2Error("invalid_token", "Token audience is not accepted", null));
    }
  }
}
