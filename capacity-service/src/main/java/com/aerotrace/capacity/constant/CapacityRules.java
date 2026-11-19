package com.aerotrace.capacity.constant;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/** Fixed values of the capacity rules. */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class CapacityRules {

  public static final String EVENT_TYPE_CAPACITY_CHANGED = "recovery.capacity.changed";
  public static final int CAPACITY_EVENT_SCHEMA_VERSION = 1;

  public static final int MAX_RESOURCE_ID_LENGTH = 32;
  public static final int MAX_SLOTS_PER_RESERVATION = 100;
  public static final int MAX_TOTAL_SLOTS = 100_000;
  public static final int MAX_IDEMPOTENCY_KEY_LENGTH = 128;

  public static final String SERVICE_TOKEN_HEADER = "X-Service-Token";
  public static final String SERVICE_ROLE = "SERVICE";

  public static final String NO_CAPACITY = "No capacity is left for this leg";
  public static final String NO_SUCH_RESERVATION = "No such reservation";
  public static final String NO_SUCH_RESOURCE = "No such capacity resource";
  public static final String RESERVATION_ID_REUSED =
      "This reservation id was used for a different request";
  public static final String IDEMPOTENCY_KEY_REUSED =
      "This Idempotency-Key was used for a different request";
  public static final String IDEMPOTENCY_KEY_INVALID =
      "Idempotency-Key must be 1 to " + MAX_IDEMPOTENCY_KEY_LENGTH + " visible ASCII characters";
  public static final String SHRINK_BELOW_HELD = "The total cannot be lower than the slots held";
  public static final String RESERVATION_CLOSED = "The reservation is already released";

  /** Reservation states. CONFIRMED is a HELD reservation that a case move has confirmed. */
  public static final String HELD = "HELD";

  public static final String RELEASED = "RELEASED";
  public static final String EXPIRED = "EXPIRED";
  public static final String CONFIRMED = "CONFIRMED";
}
