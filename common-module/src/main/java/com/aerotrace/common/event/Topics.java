package com.aerotrace.common.event;

import java.util.List;

/** Kafka topic names (spec section 8). Message key convention is noted per topic. */
public final class Topics {

  /** Key: bagTag. Producer: journey-service. */
  public static final String SCAN_RECEIVED = "baggage.scan.received.v1";

  /** Key: bagTag. Producer: risk-streams-service. */
  public static final String SCAN_ANOMALY = "baggage.scan.anomaly.v1";

  /** Key: bagTag:legId. Producer: risk-streams-service. */
  public static final String RISK_CHANGED = "baggage.risk.changed.v1";

  /** Key: caseId. Producer: recovery-service. */
  public static final String CASE_CHANGED = "recovery.case.changed.v1";

  /** Key: resourceId. Producer: capacity-service. */
  public static final String CAPACITY_CHANGED = "recovery.capacity.changed.v1";

  /** Key: eventId. Producer: failed-event handler. */
  public static final String DEAD_LETTER = "baggage.dead-letter.v1";

  public static final List<String> ALL =
      List.of(
          SCAN_RECEIVED, SCAN_ANOMALY, RISK_CHANGED, CASE_CHANGED, CAPACITY_CHANGED, DEAD_LETTER);

  private Topics() {}
}
