package com.aerotrace.gateway.config;

import com.aerotrace.common.security.Role;
import java.util.List;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.Assert;

/**
 * Gateway settings under {@code aerotrace.gateway}.
 *
 * @param audience required {@code aud} claim
 * @param publicKey inline PEM of the RSA public key that verifies the tokens, or empty
 * @param publicKeyLocation where to read that key from when none is given inline. When both are
 *     empty the copy of identity-service's {@code keys/public.pem} that is packaged into the
 *     gateway is used. The issuer is fixed ({@code JwtClaims.ISSUER}), so it is not a setting.
 * @param roleRules coarse role-to-path rules enforced at the edge; services still authorize
 * @param rateLimit token-bucket settings per caller
 */
@ConfigurationProperties("aerotrace.gateway")
public record GatewayProperties(
    String audience,
    String publicKey,
    String publicKeyLocation,
    List<RoleRule> roleRules,
    RateLimit rateLimit,
    Docs docs) {

  public GatewayProperties {
    Assert.hasText(audience, "aerotrace.gateway.audience must be set");
    if ((publicKey == null || publicKey.isBlank())
        && (publicKeyLocation == null || publicKeyLocation.isBlank())) {
      publicKeyLocation = DEFAULT_PUBLIC_KEY_LOCATION;
    }
    roleRules = roleRules == null ? List.of() : List.copyOf(roleRules);
    rateLimit = rateLimit == null ? new RateLimit(10, 20) : rateLimit;
    docs = docs == null ? new Docs(false) : docs;
  }

  /** The development public key packaged from identity-service at build time. */
  public static final String DEFAULT_PUBLIC_KEY_LOCATION = "classpath:keys/public.pem";

  /** Whether Swagger UI and the proxied service definitions are reachable without a token. */
  public record Docs(boolean enabled) {}

  /** Requests matching {@code pattern} need at least one of {@code roles}. */
  public record RoleRule(String pattern, Set<Role> roles) {}

  /** Tokens added per second and the bucket size. */
  public record RateLimit(int replenishRate, int burstCapacity) {}
}
