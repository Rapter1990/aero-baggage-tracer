package com.aerotrace.journey.constant;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/** The format rules of the domain, shared by validation annotations and code. */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class JourneyRules {

  /** IATA license plate bag tag: ten digits. */
  public static final String BAG_TAG_PATTERN = "^[0-9]{10}$";

  /** The pseudonymous reference identity-service puts in tokens: "P-" and 20 digits. */
  public static final String PASSENGER_REF_PATTERN = "^P-[0-9]{20}$";

  public static final String AIRPORT_PATTERN = "^[A-Z]{3}$";
  public static final String FLIGHT_PATTERN = "^[A-Z0-9]{2}[0-9]{1,4}[A-Z]?$";

  public static final int MAX_LEGS = 8;

  /** The most scans a journey answer lists; the newest are kept and the answer says so. */
  public static final int MAX_HISTORY = 500;

  public static final int MAX_PAYLOAD_FIELDS = 20;
  public static final int MAX_IDEMPOTENCY_KEY_LENGTH = 128;
  public static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";
  public static final String EVENT_TYPE_SCAN_RECEIVED = "baggage.scan.received";

  /** Revision 2 added the leg (legId, flightNumber, departureAt) to the scan payload. */
  public static final int SCAN_EVENT_SCHEMA_VERSION = 2;
}
