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
 * @param trustedProxies reverse proxies (addresses or CIDR networks) whose {@code X-Forwarded-For}
 *     is believed when working out the client address for rate limiting. Empty (the default) means
 *     the header is never read, so it cannot be forged to dodge a limit.
 */
@ConfigurationProperties("aerotrace.gateway")
public record GatewayProperties(
    String audience,
    String publicKey,
    String publicKeyLocation,
    List<RoleRule> roleRules,
    RateLimit rateLimit,
    Docs docs,
    List<String> trustedProxies) {

  public GatewayProperties {
    Assert.hasText(audience, "aerotrace.gateway.audience must be set");
    if ((publicKey == null || publicKey.isBlank())
        && (publicKeyLocation == null || publicKeyLocation.isBlank())) {
      publicKeyLocation = DEFAULT_PUBLIC_KEY_LOCATION;
    }
    roleRules = roleRules == null ? List.of() : List.copyOf(roleRules);
    rateLimit = rateLimit == null ? new RateLimit(10, 20, null) : rateLimit;
    docs = docs == null ? new Docs(false) : docs;
    trustedProxies = trustedProxies == null ? List.of() : List.copyOf(trustedProxies);
  }

  /** The development public key packaged from identity-service at build time. */
  public static final String DEFAULT_PUBLIC_KEY_LOCATION = "classpath:keys/public.pem";

  /** Whether Swagger UI and the proxied service definitions are reachable without a token. */
  public record Docs(boolean enabled) {}

  /** Requests matching {@code pattern} need at least one of {@code roles}. */
  public record RoleRule(String pattern, Set<Role> roles) {}

  /**
   * Tokens added per second and the bucket size, for every API call; {@code auth} is the stricter
   * bucket of each authentication endpoint (login, register, ...), per client address.
   */
  public record RateLimit(int replenishRate, int burstCapacity, Auth auth) {

    public RateLimit {
      auth = auth == null ? new Auth(0.2, 10) : auth;
    }
  }

  /** A bucket for the authentication endpoints: 0.2 tokens a second is 12 a minute. */
  public record Auth(double replenishRate, int burstCapacity) {}
}
