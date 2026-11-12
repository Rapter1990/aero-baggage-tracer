package com.aerotrace.gateway.config;

import com.aerotrace.common.security.Role;
import java.util.List;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.Assert;

/**
 * Gateway settings under {@code aerotrace.gateway}.
 *
 * @param issuer required {@code iss} claim
 * @param audience required {@code aud} claim
 * @param jwkSetUri identity-service JWKS endpoint used to verify signatures
 * @param roleRules coarse role-to-path rules enforced at the edge; services still authorize
 * @param rateLimit token-bucket settings per caller
 */
@ConfigurationProperties("aerotrace.gateway")
public record GatewayProperties(
    String issuer,
    String audience,
    String jwkSetUri,
    List<RoleRule> roleRules,
    RateLimit rateLimit,
    Docs docs) {

  public GatewayProperties {
    Assert.hasText(issuer, "aerotrace.gateway.issuer must be set");
    Assert.hasText(audience, "aerotrace.gateway.audience must be set");
    Assert.hasText(jwkSetUri, "aerotrace.gateway.jwk-set-uri must be set");
    roleRules = roleRules == null ? List.of() : List.copyOf(roleRules);
    rateLimit = rateLimit == null ? new RateLimit(10, 20) : rateLimit;
    docs = docs == null ? new Docs(false) : docs;
  }

  /** Whether Swagger UI and the proxied service definitions are reachable without a token. */
  public record Docs(boolean enabled) {}

  /** Requests matching {@code pattern} need at least one of {@code roles}. */
  public record RoleRule(String pattern, Set<Role> roles) {}

  /** Tokens added per second and the bucket size. */
  public record RateLimit(int replenishRate, int burstCapacity) {}
}
