package com.aerotrace.search.base;

import com.aerotrace.common.event.EventEnvelope;
import com.aerotrace.common.event.EventJson;
import com.aerotrace.common.event.RiskChangedPayload;
import com.aerotrace.common.event.ScanReceivedPayload;
import com.aerotrace.common.event.Topics;
import com.aerotrace.common.model.RecoveryState;
import com.aerotrace.common.model.RiskLevel;
import com.aerotrace.search.constant.SearchTopics;
import com.aerotrace.search.indexer.model.BagRegistered;
import com.aerotrace.search.indexer.model.CaseChange;
import com.aerotrace.search.indexer.model.LegSnapshot;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.kafka.clients.consumer.ConsumerRecord;

/** Event fixtures as the producers write them (envelope JSON), and records that carry them. */
public final class Events {

  private static final AtomicLong OFFSET = new AtomicLong();

  /** One departure for every leg of the fixtures: a leg departs once, whichever event says so. */
  public static final Instant DEPARTURE = Instant.parse("2026-05-01T12:00:00Z");

  private Events() {}

  public static ConsumerRecord<String, String> record(String topic, String key, String json) {
    return new ConsumerRecord<>(topic, 0, OFFSET.incrementAndGet(), key, json);
  }

  private static String envelope(
      String type, int schema, String aggregate, Instant occurredAt, String corr, Object payload) {
    return EventJson.write(
        new EventEnvelope<>(
            UUID.randomUUID().toString(),
            type,
            schema,
            aggregate,
            occurredAt,
            Instant.now(),
            corr,
            payload));
  }

  /** A revision 1 case event: only caseId, bagTag, legId, state, version, reason. */
  public static String caseV1(
      String caseId, String bagTag, String legId, RecoveryState state, long version) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("caseId", caseId);
    payload.put("bagTag", bagTag);
    payload.put("legId", legId);
    payload.put("state", state);
    payload.put("version", version);
    payload.put("reason", "risk HIGH");
    return envelope(
        SearchTopics.EVENT_TYPE_CASE_CHANGED, 1, caseId, Instant.now(), "corr-" + caseId, payload);
  }

  /** A revision 2 case event (#29): with openedAt, resolvedAt and trigger. */
  public static String caseV2(CaseChange change, Instant occurredAt, String correlationId) {
    return envelope(
        SearchTopics.EVENT_TYPE_CASE_CHANGED,
        2,
        change.caseId(),
        occurredAt,
        correlationId,
        change);
  }

  public static CaseChange change(
      String caseId,
      String bagTag,
      String legId,
      RecoveryState state,
      long version,
      Instant openedAt,
      Instant resolvedAt) {
    return new CaseChange(
        caseId, bagTag, legId, state, version, "risk HIGH", openedAt, resolvedAt, "RISK", null);
  }

  public static ConsumerRecord<String, String> caseRecord(String json) {
    return record(Topics.CASE_CHANGED, "k", json);
  }

  public static String scan(
      String bagTag, String airport, Instant occurredAt, long deviceSequence, String legId) {
    return envelope(
        SearchTopics.EVENT_TYPE_SCAN_RECEIVED,
        2,
        bagTag,
        occurredAt,
        "corr-scan",
        new ScanReceivedPayload(
            UUID.randomUUID().toString(),
            bagTag,
            airport,
            "LOADED",
            deviceSequence,
            occurredAt,
            occurredAt,
            "UTC",
            Instant.now(),
            false,
            legId,
            legId == null ? null : "TK1234",
            legId == null ? null : DEPARTURE));
  }

  public static String risk(String bagTag, String legId, RiskLevel level, Instant occurredAt) {
    return envelope(
        SearchTopics.EVENT_TYPE_RISK_CHANGED,
        1,
        bagTag + ":" + legId,
        occurredAt,
        "corr-risk",
        new RiskChangedPayload(bagTag, legId, RiskLevel.LOW, level, DEPARTURE));
  }

  public static String registered(String bagTag, List<LegSnapshot> legs, Instant occurredAt) {
    return envelope(
        SearchTopics.EVENT_TYPE_BAG_REGISTERED,
        1,
        bagTag,
        occurredAt,
        "corr-reg",
        new BagRegistered(bagTag, legs));
  }

  public static LegSnapshot leg(String legId, String flight, String origin, String destination) {
    return new LegSnapshot(
        legId,
        flight,
        flight.substring(0, 2),
        origin,
        destination,
        Instant.now().plusSeconds(7200));
  }
}
