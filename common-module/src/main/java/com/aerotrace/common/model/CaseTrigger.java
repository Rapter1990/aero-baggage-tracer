package com.aerotrace.common.model;

/**
 * What opened a recovery case. Fixed when the case is opened; it never changes afterwards.
 *
 * <ul>
 *   <li>{@code RISK}: a {@code baggage.risk.changed.v1} event raised the bag's leg to HIGH;
 *   <li>{@code SCANNER_ANOMALY}: a scanner anomaly (BR-03) opened it. Today an anomaly only flags
 *       the cases a bag already has, so no case carries this value yet; it is part of the contract
 *       so a consumer handles it when it appears;
 *   <li>{@code MANUAL}: a member of staff opened it through the API.
 * </ul>
 *
 * Like every enum on the wire it is open: a value added later reads as {@code null} (see {@code
 * EventJson}).
 */
public enum CaseTrigger {
  RISK,
  SCANNER_ANOMALY,
  MANUAL
}
