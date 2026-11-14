package com.aerotrace.journey.service;

import com.aerotrace.common.correlation.CorrelationId;
import com.aerotrace.common.error.ApiException;
import com.aerotrace.common.error.ErrorCode;
import com.aerotrace.common.event.EventEnvelope;
import com.aerotrace.common.event.EventJson;
import com.aerotrace.common.event.ScanReceivedPayload;
import com.aerotrace.common.event.Topics;
import com.aerotrace.journey.config.JourneyProperties;
import com.aerotrace.journey.constant.ApiMessages;
import com.aerotrace.journey.constant.JourneyRules;
import com.aerotrace.journey.constant.MetricNames;
import com.aerotrace.journey.model.dto.request.ScanRequest;
import com.aerotrace.journey.model.dto.response.ScanAcceptedResponse;
import com.aerotrace.journey.model.entity.BaggageEntity;
import com.aerotrace.journey.model.entity.ScanAuditEntity;
import com.aerotrace.journey.repository.BaggageRepository;
import com.aerotrace.journey.repository.OutboxRepository;
import com.aerotrace.journey.repository.ProcessedEventRepository;
import com.aerotrace.journey.repository.ScanAuditRepository;
import com.aerotrace.journey.scan.NormalizedScan;
import com.aerotrace.journey.scan.ScanHandlerFactory;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Accepts a scan: reads it with the handler of its protocol, records it in the immutable audit,
 * moves the authoritative state of the bag forward and stores the event in the outbox, all in one
 * transaction. The Idempotency-Key is recorded in the same transaction, so a retry (or a second
 * replica) gets the original answer and produces no second scan or event.
 */
@Service
public class ScanIngestionService {

  private final ScanHandlerFactory handlers;
  private final BaggageRepository baggage;
  private final ScanAuditRepository audit;
  private final ProcessedEventRepository processed;
  private final OutboxRepository outbox;
  private final JourneyProperties properties;
  private final Clock clock;
  private final MeterRegistry meters;

  public ScanIngestionService(
      ScanHandlerFactory handlers,
      BaggageRepository baggage,
      ScanAuditRepository audit,
      ProcessedEventRepository processed,
      OutboxRepository outbox,
      JourneyProperties properties,
      Clock clock,
      MeterRegistry meters) {
    this.handlers = handlers;
    this.baggage = baggage;
    this.audit = audit;
    this.processed = processed;
    this.outbox = outbox;
    this.properties = properties;
    this.clock = clock;
    this.meters = meters;
  }

  @Transactional
  public ScanAcceptedResponse ingest(ScanRequest request, String idempotencyKey) {
    NormalizedScan scan =
        handlers.create(request.protocol(), request.versionOrDefault()).handle(request.payload());
    // Locks the bag, so scans of one bag are applied one at a time, in any replica.
    BaggageEntity bag =
        baggage
            .findByBagTagForUpdate(scan.bagTag())
            .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, ApiMessages.BAG_NOT_FOUND));
    // Read after the lock, so the order of the timestamps is the order the scans were committed.
    Instant now = Instant.now(clock);
    if (scan.occurredAt().isAfter(now.plus(properties.maxClockSkew()))) {
      throw new ApiException(ErrorCode.VALIDATION_ERROR, ApiMessages.SCAN_IN_FUTURE);
    }

    if (!processed.markProcessed(idempotencyKey, now)) {
      return audit
          .findByEventId(idempotencyKey)
          .map(existing -> replay(existing, scan, request))
          .orElseThrow(
              () ->
                  new ApiException(ErrorCode.VERSION_CONFLICT, ApiMessages.IDEMPOTENCY_KEY_REUSED));
    }

    boolean late = scan.occurredAt().isBefore(now.minus(properties.lateEventGrace()));
    boolean applied = bag.accepts(scan.occurredAt(), scan.deviceSequence());
    ScanAuditEntity saved =
        audit.saveAndFlush(
            new ScanAuditEntity(
                idempotencyKey,
                scan.bagTag(),
                request.protocol().name(),
                scan.airport(),
                scan.checkpoint().name(),
                scan.deviceSequence(),
                scan.occurredAt(),
                scan.deviceTimestamp(),
                scan.sourceTimezone(),
                now,
                late,
                applied));
    if (applied) {
      bag.applyScan(
          saved.getId(),
          scan.airport(),
          scan.checkpoint().name(),
          scan.occurredAt(),
          scan.deviceSequence());
    }
    queueEvent(saved, now);
    meters.counter(MetricNames.SCANS_INGESTED, "late", String.valueOf(late)).increment();
    return response(saved, false);
  }

  private void queueEvent(ScanAuditEntity scan, Instant now) {
    ScanReceivedPayload payload =
        new ScanReceivedPayload(
            scan.getId().toString(),
            scan.getBagTag(),
            scan.getAirport(),
            scan.getCheckpoint(),
            scan.getDeviceSequence(),
            scan.getOccurredAt(),
            scan.getDeviceTimestamp(),
            scan.getSourceTimezone(),
            scan.getIngestedAt(),
            scan.isLateEvent());
    EventEnvelope<ScanReceivedPayload> envelope =
        EventEnvelope.create(
            JourneyRules.EVENT_TYPE_SCAN_RECEIVED,
            JourneyRules.SCAN_EVENT_SCHEMA_VERSION,
            scan.getBagTag(),
            MDC.get(CorrelationId.MDC_KEY),
            scan.getOccurredAt(),
            payload,
            clock);
    outbox.add(
        UUID.fromString(envelope.eventId()),
        Topics.SCAN_RECEIVED,
        scan.getBagTag(),
        EventJson.write(envelope),
        now);
  }

  /**
   * The answer to a retry. The key must belong to the same scan: a key reused for another scan
   * would otherwise swallow the new one behind a success answer, so it is a conflict, and nothing
   * of the first scan is revealed.
   */
  private static ScanAcceptedResponse replay(
      ScanAuditEntity existing, NormalizedScan scan, ScanRequest request) {
    boolean same =
        existing.getBagTag().equals(scan.bagTag())
            && existing.getProtocol().equals(request.protocol().name())
            && existing.getAirport().equals(scan.airport())
            && existing.getCheckpoint().equals(scan.checkpoint().name())
            && existing.getDeviceSequence() == scan.deviceSequence()
            && existing
                .getOccurredAt()
                .truncatedTo(ChronoUnit.MICROS)
                .equals(scan.occurredAt().truncatedTo(ChronoUnit.MICROS));
    if (!same) {
      throw new ApiException(ErrorCode.VERSION_CONFLICT, ApiMessages.IDEMPOTENCY_KEY_REUSED);
    }
    return response(existing, true);
  }

  private static ScanAcceptedResponse response(ScanAuditEntity scan, boolean duplicate) {
    return new ScanAcceptedResponse(
        scan.getId(), scan.getBagTag(), ScanAcceptedResponse.QUEUED, scan.isLateEvent(), duplicate);
  }
}
