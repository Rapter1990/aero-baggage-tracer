package com.aerotrace.common.security;

import java.util.List;
import java.util.Objects;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.util.Assert;

/**
 * Claim validation shared by the gateway and resource services: expiration, issuer, audience.
 * Signature verification is done by each service's {@code JwtDecoder} (with its RSA public key);
 * set this validator on that decoder.
 */
public final class AeroTraceJwtValidators {

  private AeroTraceJwtValidators() {}

  public static OAuth2TokenValidator<Jwt> create(String issuer, String audience) {
    Assert.hasText(issuer, "issuer must not be blank");
    Assert.hasText(audience, "audience must not be blank");
    return new DelegatingOAuth2TokenValidator<>(
        // exp is mandatory: a token without an expiry is rejected, not accepted.
        new JwtClaimValidator<Object>(JwtClaimNames.EXP, Objects::nonNull),
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
