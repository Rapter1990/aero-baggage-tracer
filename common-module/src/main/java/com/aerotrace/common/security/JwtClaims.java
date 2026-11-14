package com.aerotrace.common.security;

/** JWT claim names (and the fixed issuer) shared by identity-service and all resource servers. */
public final class JwtClaims {

  /**
   * The {@code iss} claim of every token identity-service issues, and the only issuer resource
   * servers accept. A fixed value, so no setting has to be kept equal in two services.
   */
  public static final String ISSUER = "aerotrace-identity-service";

  /** List of role names, see {@link Role}. */
  public static final String ROLES = "roles";

  /**
   * Pseudonymous passenger reference used for row-level ownership. Spelled {@code passengerRef} to
   * match the claim set identity-service issues (backlog #8); identity must use this constant.
   */
  public static final String PASSENGER_REF = "passengerRef";

  private JwtClaims() {}
}
