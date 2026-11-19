package com.aerotrace.search.indexer.model;

import com.aerotrace.common.model.RecoveryState;
import java.time.Instant;

/**
 * The payload of {@code recovery.case.changed.v1} as the search-service reads it.
 *
 * <p>Revision 1 (today) carries {@code caseId, bagTag, legId, state, version, reason}. Revision 2
 * (#29) adds {@code openedAt}, {@code resolvedAt}, {@code trigger} and {@code assignedTo}: they are
 * read here when present and are {@code null} in revision 1 events. The shared {@code
 * CaseChangedPayload} is not edited by this service; this record is its tolerant reader, and so the
 * same shape is used for a case from the bulk export. {@code trigger} is kept as text so a value
 * added later cannot make an event unreadable. {@code assignedTo} is read but never indexed.
 */
public record CaseChange(
    String caseId,
    String bagTag,
    String legId,
    RecoveryState state,
    long version,
    String reason,
    Instant openedAt,
    Instant resolvedAt,
    String trigger,
    String assignedTo) {}
