package com.aerotrace.recovery.consumer;

import com.aerotrace.common.correlation.CorrelationScope;
import com.aerotrace.common.event.CapacityChangedPayload;
import com.aerotrace.common.event.EventEnvelope;
import com.aerotrace.recovery.constant.RecoveryRules;
import com.aerotrace.recovery.repository.CapacitySnapshotRepository;
import com.aerotrace.recovery.repository.ProcessedEventRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Handles {@code recovery.capacity.changed.v1} by keeping the latest capacity of each resource, so
 * the recovery action can be chosen with a view of the free capacity. The events of one resource
 * are ordered by their {@code occurredAt}: an older event that arrives late never overwrites a
 * newer one. Nothing is reserved on this data; the capacity-service stays the only authority.
 */
@Component
public class CapacityEventHandler {

  /** What an event led to. */
  public enum Outcome {
    SNAPSHOT_UPDATED,
    STALE_EVENT,
    DUPLICATE
  }

  /** How far past our own clock an event time may be before the event is refused. */
  private static final java.time.Duration MAX_CLOCK_SKEW = java.time.Duration.ofMinutes(5);

  private final CapacitySnapshotRepository snapshots;
  private final ProcessedEventRepository processed;
  private final Clock clock;
  private final MeterRegistry meters;
  private final TransactionTemplate transaction;

  public CapacityEventHandler(
      CapacitySnapshotRepository snapshots,
      ProcessedEventRepository processed,
      Clock clock,
      MeterRegistry meters,
      PlatformTransactionManager transactionManager) {
    this.snapshots = snapshots;
    this.processed = processed;
    this.clock = clock;
    this.meters = meters;
    this.transaction = new TransactionTemplate(transactionManager);
  }

  public Outcome handle(String json) {
    EventEnvelope<CapacityChangedPayload> event =
        EventReader.read(
            json, RecoveryRules.EVENT_TYPE_CAPACITY_CHANGED, CapacityChangedPayload.class);
    validate(event.payload());
    // An event time far in the future would win every later comparison and freeze the snapshot of
    // the resource, so a clock that is that wrong makes the event unusable.
    if (event.occurredAt().isAfter(Instant.now(clock).plus(MAX_CLOCK_SKEW))) {
      throw new PoisonEventException("occurredAt is in the future");
    }
    // Its log lines join the trace of the change that caused the event.
    try (CorrelationScope ignored = CorrelationScope.of(event.correlationId())) {
      Outcome outcome = transaction.execute(status -> process(event));
      meters.counter("recovery.capacity.events", "outcome", outcome.name()).increment();
      return outcome;
    }
  }

  private Outcome process(EventEnvelope<CapacityChangedPayload> event) {
    Instant now = Instant.now(clock);
    if (!processed.markProcessed(EventReader.dedupeKey(event), now)) {
      return Outcome.DUPLICATE;
    }
    CapacityChangedPayload capacity = event.payload();
    int written =
        snapshots.upsert(
            capacity.resourceId(),
            capacity.totalSlots(),
            capacity.remainingSlots(),
            event.occurredAt(),
            now);
    return written == 1 ? Outcome.SNAPSHOT_UPDATED : Outcome.STALE_EVENT;
  }

  private static void validate(CapacityChangedPayload capacity) {
    if (capacity.resourceId() == null
        || capacity.resourceId().isBlank()
        || capacity.resourceId().length() > RecoveryRules.MAX_RESOURCE_ID_LENGTH) {
      throw new PoisonEventException("resourceId is missing or too long");
    }
    if (capacity.totalSlots() < 0
        || capacity.remainingSlots() < 0
        || capacity.remainingSlots() > capacity.totalSlots()) {
      throw new PoisonEventException("slot counts are inconsistent");
    }
  }
}
