package com.aerotrace.recovery.repository;

import com.aerotrace.recovery.model.entity.RecoveryCaseEntity;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * The event time of the newest risk event per bag and leg. It has no entity of its own: it is only
 * ever written by one atomic statement, and the repository is bound to a case entity because Spring
 * Data needs a domain type.
 */
public interface RiskEventClockRepository extends Repository<RecoveryCaseEntity, UUID> {

  /**
   * Advances the clock of a bag and leg to the event time, unless a newer event was applied before.
   * One atomic statement, so replicas cannot interleave. Returns 1 when the event is the newest (it
   * must be applied) and 0 when it is older (it is stale).
   */
  @Modifying
  @Query(
      value =
          "INSERT INTO recovery.risk_event_clock (bag_tag, leg_id, occurred_at)"
              + " VALUES (:bagTag, :legId, :occurredAt)"
              + " ON CONFLICT (bag_tag, leg_id) DO UPDATE SET occurred_at = EXCLUDED.occurred_at"
              + " WHERE recovery.risk_event_clock.occurred_at <= EXCLUDED.occurred_at",
      nativeQuery = true)
  int advance(
      @Param("bagTag") String bagTag,
      @Param("legId") String legId,
      @Param("occurredAt") Instant occurredAt);
}
