package com.aerotrace.identity.constant;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/** Limits of the authentication audit trail. */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class AuditConstants {

  /** The audit column's width; a longer name is cut rather than rejected. */
  public static final int MAX_USERNAME_LENGTH = 128;
}
