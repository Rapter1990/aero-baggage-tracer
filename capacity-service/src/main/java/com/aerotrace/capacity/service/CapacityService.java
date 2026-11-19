package com.aerotrace.capacity.service;

import com.aerotrace.audit.SecurityAuditorAware;
import com.aerotrace.capacity.config.CapacityProperties;
import com.aerotrace.capacity.constant.CapacityRules;
import com.aerotrace.capacity.constant.MetricNames;
import com.aerotrace.capacity.model.dto.ReservationRequest;
import com.aerotrace.capacity.model.dto.ReservationResponse;
import com.aerotrace.capacity.model.dto.ResourceResponse;
import com.aerotrace.capacity.model.entity.CapacityReservationEntity;
import com.aerotrace.capacity.model.entity.CapacityResourceEntity;
import com.aerotrace.capacity.model.entity.OutboxEventEntity;
import com.aerotrace.capacity.repository.CapacityReservationRepository;
import com.aerotrace.capacity.repository.CapacityResourceRepository;
import com.aerotrace.capacity.repository.OutboxEventRepository;
import com.aerotrace.common.correlation.CorrelationId;
import com.aerotrace.common.error.ApiException;
import com.aerotrace.common.error.ErrorCode;
import com.aerotrace.common.event.CapacityChangedPayload;
import com.aerotrace.common.event.EventEnvelope;
import com.aerotrace.common.event.EventJson;
import com.aerotrace.common.event.Topics;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.IntSupplier;
import org.slf4j.MDC;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Holds and returns slots. BR-04: the count of a resource is changed only by a conditional UPDATE
 * in PostgreSQL ({@code WHERE remaining_slots >= :slots}), so the database decides, whatever the
 * number of parallel requests or replicas. Nothing else (a cache, a hint) can grant capacity.
 *
 * <p>Every change of a count writes its {@code recovery.capacity.changed.v1} event to the outbox in
 * the same transaction. A hold is closed once: the guarded UPDATE that moves it from HELD decides,
 * and only the call that won returns the slots.
 */
@Service
public class CapacityService {

  private final CapacityResourceRepository resources;
  private final CapacityReservationRepository reservations;
  private final OutboxEventRepository outbox;
  private final CapacityProperties properties;
  private final Clock clock;
  private final MeterRegistry meters;
  private final TransactionTemplate transaction;

  public CapacityService(
      CapacityResourceRepository resources,
      CapacityReservationRepository reservations,
      OutboxEventRepository outbox,
      CapacityProperties properties,
      Clock clock,
      MeterRegistry meters,
      PlatformTransactionManager transactionManager) {
    this.resources = resources;
    this.reservations = reservations;
    this.outbox = outbox;
    this.properties = properties;
    this.clock = clock;
    this.meters = meters;
    this.transaction = new TransactionTemplate(transactionManager);
  }

  // ------------------------------------------------------------------ reservations

  /**
   * Holds slots. A repeated request (same reservation id, same content) answers with the existing
   * hold and takes nothing more; 409 CAPACITY_EXCEEDED when the slots are not there.
   */
  public ReservationResponse reserve(ReservationRequest request, String idempotencyKey) {
    CapacityReservationEntity existing =
        reservations.findById(request.reservationId()).orElse(null);
    if (existing != null) {
      return replay(existing, request, idempotencyKey);
    }
    if (idempotencyKey != null && reservations.findByIdempotencyKey(idempotencyKey).isPresent()) {
      throw new ApiException(ErrorCode.VERSION_CONFLICT, CapacityRules.IDEMPOTENCY_KEY_REUSED);
    }
    try {
      ReservationResponse held = transaction.execute(status -> hold(request, idempotencyKey));
      meters.counter(MetricNames.RESERVATIONS_CREATED).increment();
      return held;
    } catch (DataIntegrityViolationException raced) {
      // The same id (or key) was inserted by a parallel request. This transaction rolled back, so
      // the slots it took were never kept; answer from the winner's row.
      return reservations
          .findById(request.reservationId())
          .map(winner -> replay(winner, request, idempotencyKey))
          .orElseThrow(
              () ->
                  new ApiException(
                      ErrorCode.VERSION_CONFLICT, CapacityRules.IDEMPOTENCY_KEY_REUSED));
    }
  }

  private ReservationResponse hold(ReservationRequest request, String idempotencyKey) {
    Instant now = Instant.now(clock);
    String actor = SecurityAuditorAware.currentAuditor();
    if (resources.take(request.legId(), request.slots(), now, actor) == 0) {
      // A retry that waited for the original's row lock finds the leg full because of the original
      // hold: answer with that hold, not with "full".
      CapacityReservationEntity original =
          reservations.findById(request.reservationId()).orElse(null);
      if (original != null) {
        return replay(original, request, idempotencyKey);
      }
      if (!resources.existsById(request.legId())) {
        // A leg nobody provisioned is a set-up problem, not a full leg: say so, so it is not
        // counted as capacity running out.
        meters.counter(MetricNames.RESERVATION_NOT_PROVISIONED).increment();
        throw new ApiException(ErrorCode.CAPACITY_NOT_PROVISIONED, CapacityRules.NOT_PROVISIONED);
      }
      meters.counter(MetricNames.RESERVATION_CONFLICTS).increment();
      throw new ApiException(ErrorCode.CAPACITY_EXCEEDED, CapacityRules.NO_CAPACITY);
    }
    reservations.insertHeld(
        request.reservationId(),
        request.legId(),
        request.caseId(),
        request.bagTag(),
        request.slots(),
        idempotencyKey,
        now,
        actor);
    publishChange(request.legId());
    return new ReservationResponse(request.reservationId(), CapacityRules.HELD);
  }

  private static ReservationResponse replay(
      CapacityReservationEntity existing, ReservationRequest request, String idempotencyKey) {
    if (existing.getResourceId() == null) {
      // A tombstone: the id was released before this request got here, so it must never hold.
      throw new ApiException(ErrorCode.VERSION_CONFLICT, CapacityRules.RESERVATION_CLOSED);
    }
    boolean sameRequest =
        existing.getResourceId().equals(request.legId())
            && existing.getSlots() == request.slots()
            && existing.getCaseId().equals(request.caseId())
            && existing.getBagTag().equals(request.bagTag());
    if (!sameRequest) {
      throw new ApiException(ErrorCode.VERSION_CONFLICT, CapacityRules.RESERVATION_ID_REUSED);
    }
    if (idempotencyKey != null
        && existing.getIdempotencyKey() != null
        && !idempotencyKey.equals(existing.getIdempotencyKey())) {
      throw new ApiException(ErrorCode.VERSION_CONFLICT, CapacityRules.IDEMPOTENCY_KEY_REUSED);
    }
    if (!CapacityRules.HELD.equals(existing.getStatus())) {
      // A late retry of a hold that was already given back must not look like a live hold.
      throw new ApiException(ErrorCode.VERSION_CONFLICT, CapacityRules.RESERVATION_CLOSED);
    }
    return toResponse(existing);
  }

  /**
   * Releases a hold and returns its slots, once. Releasing a hold that is already closed changes
   * nothing and answers true; false means there was no such hold (the caller treats that as
   * released).
   *
   * <p>A release of an unknown id records a RELEASED tombstone under that id. The caller releases
   * after a timeout, when the hold it asked for may still be on its way; the late hold then meets
   * the tombstone on the primary key and is refused, so it can never become a hold nobody owns.
   */
  public boolean release(UUID reservationId) {
    return Boolean.TRUE.equals(
        transaction.execute(
            status -> {
              String actor = SecurityAuditorAware.currentAuditor();
              IntSupplier close =
                  () -> reservations.release(reservationId, Instant.now(clock), actor);
              boolean known = settle(reservationId, close, MetricNames.RESERVATIONS_RELEASED);
              if (!known
                  && reservations.insertTombstone(reservationId, Instant.now(clock), actor) == 0) {
                // The hold was inserted between the look-up and the tombstone (the insert waited
                // for
                // it to commit): it exists now, so close it.
                known = settle(reservationId, close, MetricNames.RESERVATIONS_RELEASED);
              }
              return known;
            }));
  }

  /** Says that a case move kept the hold, so the expiry job leaves it alone. */
  public ReservationResponse confirm(UUID reservationId) {
    return transaction.execute(
        status -> {
          if (!reservations.existsById(reservationId)) {
            throw new ApiException(ErrorCode.NOT_FOUND, CapacityRules.NO_SUCH_RESERVATION);
          }
          reservations.confirm(reservationId, Instant.now(clock));
          CapacityReservationEntity current = reservations.findById(reservationId).orElseThrow();
          if (!CapacityRules.HELD.equals(current.getStatus())) {
            throw new ApiException(ErrorCode.ILLEGAL_TRANSITION, CapacityRules.RESERVATION_CLOSED);
          }
          return toResponse(current);
        });
  }

  /**
   * Removes the holds that nobody confirmed within the time to live and returns their slots. A hold
   * a case move confirmed is never touched. Returns how many holds expired.
   */
  public int expireStale() {
    Instant cutoff = Instant.now(clock).minus(properties.hold().ttl());
    List<UUID> candidates =
        reservations.findExpirable(cutoff, 100).stream()
            .map(CapacityReservationEntity::getId)
            .toList();
    int expired = 0;
    for (UUID id : candidates) {
      Boolean closed =
          transaction.execute(
              status ->
                  settle(
                      id,
                      () -> reservations.expire(id, cutoff, Instant.now(clock)),
                      MetricNames.RESERVATIONS_EXPIRED));
      if (Boolean.TRUE.equals(closed)) {
        expired++;
      }
    }
    return expired;
  }

  /**
   * Runs the guarded close and, only when this call won it, returns the slots and writes the event.
   * Returns false when there is no such hold.
   */
  private boolean settle(UUID reservationId, IntSupplier close, String counter) {
    CapacityReservationEntity hold = reservations.findById(reservationId).orElse(null);
    if (hold == null) {
      return false;
    }
    if (close.getAsInt() == 1) {
      Instant now = Instant.now(clock);
      resources.giveBack(
          hold.getResourceId(), hold.getSlots(), now, SecurityAuditorAware.currentAuditor());
      publishChange(hold.getResourceId());
      meters.counter(counter).increment();
    }
    return true;
  }

  private static ReservationResponse toResponse(CapacityReservationEntity hold) {
    String state =
        CapacityRules.HELD.equals(hold.getStatus()) && hold.getConfirmedAt() != null
            ? CapacityRules.CONFIRMED
            : hold.getStatus();
    return new ReservationResponse(hold.getId(), state);
  }

  // ------------------------------------------------------------------ resources

  /**
   * Creates a resource or sets its total (admin). The free count moves by the same difference; a
   * total lower than the slots already held is refused (409 CAPACITY_EXCEEDED).
   */
  public ResourceResponse setTotal(String resourceId, int totalSlots) {
    return transaction.execute(
        status -> {
          Instant now = Instant.now(clock);
          CapacityResourceEntity current = resources.findById(resourceId).orElse(null);
          if (current != null && current.getTotalSlots() == totalSlots) {
            // Nothing changes, so no event: it would only make consumers write the same snapshot.
            return ResourceResponse.of(current);
          }
          String actor = SecurityAuditorAware.currentAuditor();
          if (resources.createIfAbsent(resourceId, totalSlots, now, actor) == 0
              && resources.resize(resourceId, totalSlots, now, actor) == 0) {
            throw new ApiException(ErrorCode.CAPACITY_EXCEEDED, CapacityRules.SHRINK_BELOW_HELD);
          }
          publishChange(resourceId);
          return ResourceResponse.of(resources.findById(resourceId).orElseThrow());
        });
  }

  public ResourceResponse getResource(String resourceId) {
    return resources
        .findById(resourceId)
        .map(ResourceResponse::of)
        .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, CapacityRules.NO_SUCH_RESOURCE));
  }

  /** One page of resources, ordered by id so a page never shifts between two requests. */
  public List<ResourceResponse> listResources(int page, int size) {
    int boundedSize = Math.min(Math.max(size, 1), CapacityRules.MAX_PAGE_SIZE);
    return resources
        .findAll(PageRequest.of(Math.max(page, 0), boundedSize, Sort.by("id")))
        .map(ResourceResponse::of)
        .getContent();
  }

  // ------------------------------------------------------------------ events

  /**
   * Stores the current counts of the resource as an event, in the caller's transaction. It is
   * called after the guarded UPDATE has locked the resource row, and it takes the time only now:
   * the consumer orders events of a resource by {@code occurredAt}, so a time taken before waiting
   * for the lock could stamp a later change with an earlier time and make it look stale. The write
   * that follows the lock is also what keeps the outbox order equal to the commit order per
   * resource.
   */
  private void publishChange(String resourceId) {
    Instant now = Instant.now(clock);
    CapacityResourceEntity resource = resources.findById(resourceId).orElseThrow();
    CapacityChangedPayload payload =
        new CapacityChangedPayload(
            resource.getId(), resource.getTotalSlots(), resource.getRemainingSlots());
    EventEnvelope<CapacityChangedPayload> envelope =
        EventEnvelope.create(
            CapacityRules.EVENT_TYPE_CAPACITY_CHANGED,
            CapacityRules.CAPACITY_EVENT_SCHEMA_VERSION,
            resource.getId(),
            MDC.get(CorrelationId.MDC_KEY),
            now,
            payload,
            clock);
    outbox.save(
        new OutboxEventEntity(
            Topics.CAPACITY_CHANGED, resource.getId(), EventJson.write(envelope), now));
  }
}
