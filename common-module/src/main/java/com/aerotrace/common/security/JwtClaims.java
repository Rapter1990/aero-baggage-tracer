package com.aerotrace.common.security;

/** JWT claim names shared by identity-service (issuer) and all resource servers. */
public final class JwtClaims {

  /** List of role names, see {@link Role}. */
  public static final String ROLES = "roles";

  /** Pseudonymous passenger reference used for row-level ownership. */
  public static final String PASSENGER_REF = "passenger_ref";

  private JwtClaims() {}
}
