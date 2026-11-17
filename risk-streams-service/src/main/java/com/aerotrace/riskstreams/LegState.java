package com.aerotrace.riskstreams;

import com.aerotrace.common.model.Checkpoint;
import com.aerotrace.common.model.RiskLevel;
import java.time.Instant;

/**
 * Per-leg state, keyed by {@code bagTag:legId}. Scans without a leg create no state (an earlier
 * version kept a bag-level pseudo-leg; such entries are no longer read and just expire).
 *
 * @param checkpoint latest legitimate checkpoint, by event time
 * @param latestAt event time of that checkpoint
 * @param airport airport of the latest legitimate scan; selects the threshold
 * @param departureAt scheduled departure
 * @param loaded a non-rejected LOADED scan was seen: the leg cannot be HIGH any more
 * @param lastLevel last level emitted on risk.changed
 * @param expiresAt when the leg is evicted: departureAt, or latestAt + dedup retention
 */
record LegState(
    String bagTag,
    String legId,
    String airport,
    Checkpoint checkpoint,
    Instant latestAt,
    Instant departureAt,
    boolean loaded,
    RiskLevel lastLevel,
    Instant expiresAt) {

  boolean hasRisk() {
    return departureAt != null;
  }

  LegState advance(String newAirport, Checkpoint newCheckpoint, Instant at, Instant departure) {
    return new LegState(
        bagTag,
        legId,
        newAirport,
        newCheckpoint,
        at,
        departure == null ? departureAt : departure,
        loaded,
        lastLevel,
        expiresAt);
  }

  LegState withLoaded() {
    return new LegState(
        bagTag, legId, airport, checkpoint, latestAt, departureAt, true, lastLevel, expiresAt);
  }

  LegState withLevel(RiskLevel level) {
    return new LegState(
        bagTag, legId, airport, checkpoint, latestAt, departureAt, loaded, level, expiresAt);
  }

  LegState withExpiry(Instant expiry) {
    return new LegState(
        bagTag, legId, airport, checkpoint, latestAt, departureAt, loaded, lastLevel, expiry);
  }
}
