package com.aerotrace.riskstreams.support;

import com.aerotrace.common.event.EventEnvelope;
import com.aerotrace.common.event.EventJson;
import com.aerotrace.common.event.ScanReceivedPayload;
import com.aerotrace.common.model.Checkpoint;
import java.time.Instant;

/** Fluent builder of a baggage.scan.received.v1 event (schema revision 2 by default). */
public final class ScanBuilder {

  private final String scanEventId;
  private String eventId;
  private String payloadScanEventId;
  private String bagTag = "BAG-1";
  private String airport = "IST";
  private String checkpoint = Checkpoint.CHECK_IN.name();
  private Instant occurredAt = Fixtures.T0;
  private String legId = "LEG-1";
  private String flightNumber = "TK1";
  private Instant departureAt = Fixtures.T0.plusSeconds(4 * 3600);
  private int schemaVersion = 2;

  ScanBuilder(String scanEventId) {
    this.scanEventId = scanEventId;
    this.payloadScanEventId = scanEventId;
    this.eventId = "evt-" + scanEventId;
  }

  public ScanBuilder bag(String value) {
    this.bagTag = value;
    return this;
  }

  public ScanBuilder airport(String value) {
    this.airport = value;
    return this;
  }

  public ScanBuilder checkpoint(Checkpoint value) {
    this.checkpoint = value.name();
    return this;
  }

  public ScanBuilder checkpointText(String value) {
    this.checkpoint = value;
    return this;
  }

  public ScanBuilder at(Instant value) {
    this.occurredAt = value;
    return this;
  }

  public ScanBuilder leg(String value) {
    this.legId = value;
    return this;
  }

  public ScanBuilder departure(Instant value) {
    this.departureAt = value;
    return this;
  }

  /** The producer could not place the scan on a leg (also the shape of revision 1 events). */
  public ScanBuilder withoutLeg() {
    this.legId = null;
    this.flightNumber = null;
    this.departureAt = null;
    return this;
  }

  public ScanBuilder schemaVersion(int value) {
    this.schemaVersion = value;
    return this;
  }

  /** A second delivery of the same scan: same scanEventId in the payload, new envelope eventId. */
  public ScanBuilder redelivery() {
    this.eventId = "evt-redelivery-" + scanEventId;
    return this;
  }

  public ScanBuilder withoutScanEventId() {
    this.payloadScanEventId = null;
    return this;
  }

  public String bagTag() {
    return bagTag;
  }

  public String eventId() {
    return eventId;
  }

  public ScanReceivedPayload payload() {
    return new ScanReceivedPayload(
        payloadScanEventId,
        bagTag,
        airport,
        checkpoint,
        1L,
        occurredAt,
        occurredAt,
        "UTC",
        occurredAt,
        false,
        legId,
        flightNumber,
        departureAt);
  }

  public String json() {
    EventEnvelope<ScanReceivedPayload> envelope =
        new EventEnvelope<>(
            eventId,
            "baggage.scan.received",
            schemaVersion,
            bagTag,
            occurredAt,
            occurredAt,
            "corr-" + scanEventId,
            payload());
    return EventJson.write(envelope);
  }
}
