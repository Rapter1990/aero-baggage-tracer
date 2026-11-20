package com.aerotrace.search.indexer;

import com.aerotrace.common.event.LegSnapshot;
import com.aerotrace.search.elastic.Elastic;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * One change to a bag's journey document, applied by {@link #SCRIPT} as a scripted upsert. The
 * document is written by four sources with unrelated clocks, so no single external version fits;
 * each part is guarded by its own clock instead (ADR 0004, decision 5):
 *
 * <ul>
 *   <li>the latest scan is replaced only by a newer {@code (occurredAt, deviceSequence)}, the
 *       journey-service's own rule;
 *   <li>a leg's risk only by a newer risk event ({@code occurredAt}), the recovery-service's risk
 *       clock;
 *   <li>a leg's case only by a higher version of the same case, or a case opened later;
 *   <li>itinerary attributes (flight, airline, origin, destination, departure) fill in or correct
 *       the leg; they come from the owner and carry no clock of their own.
 * </ul>
 *
 * A change that alters nothing is a {@code noop}: replaying, reordering or overlapping a snapshot
 * with live events gives the same document.
 */
public record JourneyChange(String bagTag, Map<String, Object> params) {

  static final String SCRIPT =
      """
      def s = ctx._source;
      boolean changed = false;
      if (s.docId == null) { s.docId = params.bagTag; s.bagTag = params.bagTag; changed = true; }
      if (s.legs == null) { s.legs = new ArrayList(); }
      List incoming = new ArrayList();
      if (params.legs != null) { incoming.addAll(params.legs); }
      else if (params.legId != null) { Map one = new HashMap(); one.put('legId', params.legId); incoming.add(one); }
      Map legOf = new HashMap();
      for (def p : incoming) {
        def leg = null;
        for (def l : s.legs) { if (l.legId == p.legId) { leg = l; break; } }
        if (leg == null) { leg = new HashMap(); leg.put('legId', p.legId); s.legs.add(leg); changed = true; }
        for (String f : ['flightNumber', 'airline', 'origin', 'destination', 'departureAt']) {
          if (p.containsKey(f) && p[f] != null && p[f] != leg[f]) { leg[f] = p[f]; changed = true; }
        }
        legOf.put(p.legId, leg);
      }
      def leg = params.legId == null ? null : legOf.get(params.legId);
      if (params.kind == 'scan') {
        def cur = s.latestScan;
        if (cur == null || params.occurredAt > cur.occurredAt
            || (params.occurredAt == cur.occurredAt && params.deviceSequence > cur.deviceSequence)) {
          Map scan = new HashMap();
          scan.put('airport', params.airport);
          scan.put('checkpoint', params.checkpoint);
          scan.put('legId', params.legId);
          scan.put('occurredAt', params.occurredAt);
          scan.put('deviceSequence', params.deviceSequence);
          s.latestScan = scan;
          changed = true;
        }
      } else if (params.kind == 'risk') {
        if (leg.riskAt == null || params.occurredAt > leg.riskAt) {
          leg.riskLevel = params.riskLevel; leg.riskAt = params.occurredAt; changed = true;
        }
      } else if (params.kind == 'case') {
        boolean newer;
        if (leg.caseId == null) { newer = true; }
        else if (leg.caseId == params.caseId) { newer = params.caseVersion > leg.caseVersion; }
        else { newer = params.caseAt > leg.caseAt; }
        if (newer) {
          leg.caseId = params.caseId; leg.caseState = params.caseState;
          leg.caseVersion = params.caseVersion; leg.caseAt = params.caseAt; changed = true;
        }
      }
      if (changed) {
        if (params.eventAt != null && (s.updatedAt == null || params.eventAt > s.updatedAt)) {
          s.updatedAt = params.eventAt;
        }
      } else {
        ctx.op = 'noop';
      }
      """;

  private static Map<String, Object> base(String kind, String bagTag, Instant eventAt) {
    Map<String, Object> params = new HashMap<>();
    params.put("kind", kind);
    params.put("bagTag", bagTag);
    params.put("eventAt", Elastic.millis(eventAt));
    return params;
  }

  /** A scan event; flight and departure fill in the leg when the scan names one. */
  public static JourneyChange scan(
      String bagTag,
      String airport,
      String checkpoint,
      long deviceSequence,
      Instant occurredAt,
      String legId,
      String flightNumber,
      Instant departureAt) {
    Map<String, Object> params = base("scan", bagTag, occurredAt);
    params.put("airport", airport);
    params.put("checkpoint", checkpoint);
    params.put("deviceSequence", deviceSequence);
    params.put("occurredAt", Elastic.millis(occurredAt));
    params.put("legId", legId);
    if (legId != null) {
      Map<String, Object> leg = new HashMap<>();
      leg.put("legId", legId);
      leg.put("flightNumber", flightNumber);
      leg.put("departureAt", Elastic.millis(departureAt));
      params.put("legs", List.of(leg));
    }
    return new JourneyChange(bagTag, params);
  }

  /** A risk event of one leg. */
  public static JourneyChange risk(
      String bagTag, String legId, String riskLevel, Instant occurredAt, Instant departureAt) {
    Map<String, Object> params = base("risk", bagTag, occurredAt);
    params.put("legId", legId);
    params.put("riskLevel", riskLevel);
    params.put("occurredAt", Elastic.millis(occurredAt));
    Map<String, Object> leg = new HashMap<>();
    leg.put("legId", legId);
    leg.put("departureAt", Elastic.millis(departureAt));
    params.put("legs", List.of(leg));
    return new JourneyChange(bagTag, params);
  }

  /** The case of one leg. */
  public static JourneyChange caseState(
      String bagTag,
      String legId,
      String caseId,
      String state,
      long version,
      Instant caseAt,
      Instant eventAt) {
    Map<String, Object> params = base("case", bagTag, eventAt);
    params.put("legId", legId);
    params.put("caseId", caseId);
    params.put("caseState", state);
    params.put("caseVersion", version);
    params.put("caseAt", Elastic.millis(caseAt));
    return new JourneyChange(bagTag, params);
  }

  /** The itinerary of a bag (registration or the bag export). */
  public static JourneyChange itinerary(String bagTag, List<LegSnapshot> legs, Instant eventAt) {
    Map<String, Object> params = base("itinerary", bagTag, eventAt);
    List<Map<String, Object>> snapshot = new ArrayList<>();
    for (LegSnapshot leg : legs) {
      Map<String, Object> one = new HashMap<>();
      one.put("legId", leg.legId());
      one.put("flightNumber", leg.flightNumber());
      one.put("airline", leg.airline());
      one.put("origin", leg.origin());
      one.put("destination", leg.destination());
      one.put("departureAt", Elastic.millis(leg.departureAt()));
      snapshot.add(one);
    }
    params.put("legs", snapshot);
    return new JourneyChange(bagTag, params);
  }
}
