package com.aerotrace.recovery.constant;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/** Rules, limits and messages of the recovery service. Messages never carry personal data. */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class RecoveryRules {

  public static final String BAG_TAG_PATTERN = "^[0-9]{10}$";
  public static final String LEG_ID_PATTERN = "^[A-Za-z0-9_-]{1,32}$";
  public static final int MAX_REASON_LENGTH = 500;
  public static final int MAX_IDEMPOTENCY_KEY_LENGTH = 128;
  public static final int MAX_PAGE_SIZE = 100;
  public static final int DEFAULT_PAGE_SIZE = 20;

  public static final String EVENT_TYPE_CASE_CHANGED = "recovery.case.changed";
  public static final int CASE_EVENT_SCHEMA_VERSION = 1;

  /** Actor recorded when no authenticated caller is known (for example an event consumer). */
  public static final String SYSTEM_ACTOR = "system";

  public static final String BAG_TAG_RULE = "bagTag must be a 10-digit bag tag";
  public static final String LEG_ID_RULE = "legId must be 1-32 letters, digits, '-' or '_'";
  public static final String REASON_REQUIRED = "A reason is required";
  public static final String CASE_NOT_FOUND = "No such recovery case";
  public static final String ACTIVE_CASE_EXISTS =
      "An active recovery case already exists for this bag and leg";
  public static final String VERSION_MISMATCH =
      "The case was changed by someone else; reload it and try again";
  public static final String IDEMPOTENCY_KEY_REUSED =
      "This Idempotency-Key was already used for a different request";
  public static final String IDEMPOTENCY_KEY_INVALID =
      "Idempotency-Key must be 1-128 visible ASCII characters";
  public static final String ILLEGAL_MOVE = "This move is not allowed from the current state";
  public static final String POLICY_VERSION_MISMATCH =
      "The policy was changed by someone else; reload it and try again";
  public static final String HIGH_CONNECTION_RISK = "HIGH_CONNECTION_RISK";

  /** Reason recorded when a risk cleared before anyone started on the case. */
  public static final String RISK_CLEARED =
      "RISK_CLEARED: the connection risk dropped before the case was worked on";

  /** Reason recorded when a scanner anomaly was flagged on the bag of an active case (BR-03). */
  public static final String SCANNER_ANOMALY =
      "SCANNER_ANOMALY: repeated rejected scans, an agent must check the bag";

  public static final String EVENT_TYPE_SCAN_ANOMALY = "baggage.scan.anomaly";
  public static final String EVENT_TYPE_CAPACITY_CHANGED = "recovery.capacity.changed";
  public static final int MAX_RESOURCE_ID_LENGTH = 64;

  /** Length of the actor / created_by columns. */
  public static final int MAX_ACTOR_LENGTH = 100;

  public static final String EVENT_TYPE_RISK_CHANGED = "baggage.risk.changed";
  public static final String EVENT_TYPE_DEAD_LETTER = "baggage.dead-letter";
  public static final int DEAD_LETTER_SCHEMA_VERSION = 1;

  public static final String CAPACITY_EXCEEDED = "No recovery capacity is left";
  public static final String CAPACITY_UNAVAILABLE =
      "Recovery capacity could not be checked right now; try again shortly";

  /** Reason recorded when a hold ran out and the case was cancelled (AT-12). */
  public static final String RESERVATION_TIMEOUT =
      "RESERVATION_TIMEOUT: the capacity hold expired and was released";

  public static final String CASE_IS_FINAL = "The case is closed; a closed case cannot be reopened";
}
