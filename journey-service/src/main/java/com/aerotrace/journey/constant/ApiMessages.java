package com.aerotrace.journey.constant;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/** Texts that reach API clients. All are fixed: none contains anything the client sent. */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class ApiMessages {

  public static final String AUTHENTICATION_REQUIRED = "Authentication required";
  public static final String INSUFFICIENT_PERMISSIONS = "Insufficient permissions";

  public static final String BAG_TAG_DUPLICATE = "That bag tag is already registered";
  public static final String BAG_NOT_FOUND = "Bag not found";
  public static final String BAG_TAG_RULE = "must be a 10-digit IATA bag tag";
  public static final String PASSENGER_REF_RULE =
      "must be a passenger reference (P- and 20 digits)";
  public static final String AIRPORT_RULE = "must be a 3-letter uppercase IATA airport code";
  public static final String FLIGHT_RULE = "must be an airline code and flight number, e.g. TK1234";
  public static final String LEGS_RULE = "must contain 1 to " + JourneyRules.MAX_LEGS + " legs";

  public static final String LEG_AIRPORTS = "A leg must leave and arrive at different airports";
  public static final String LEG_TIMES = "A leg must arrive after it departs";
  public static final String LEG_CONTINUITY =
      "Each leg must depart from the airport the previous leg arrived at";
  public static final String LEG_ORDER = "Legs must be in chronological order";
  public static final String MINIMUM_CONNECTION =
      "A connection is shorter than the minimum connection time";

  public static final String IDEMPOTENCY_KEY_REQUIRED =
      "The Idempotency-Key header is required (1-128 visible characters)";
  public static final String IDEMPOTENCY_KEY_REUSED =
      "The Idempotency-Key was already used for a different scan";
  public static final String SCAN_VERSION_UNSUPPORTED = "That scan format version is not supported";
  public static final String SCAN_PAYLOAD_INVALID =
      "The scan payload is missing or has an invalid field";
  public static final String SCAN_IN_FUTURE = "The scan is stamped in the future";
}
