package com.aerotrace.common.event;

import com.aerotrace.common.model.CaseTrigger;
import com.aerotrace.common.model.RecoveryState;
import java.time.Instant;

/**
 * Payload of {@link Topics#CASE_CHANGED}, and the shape of one case in the recovery-service's
 * internal export ({@code GET /internal/v1/export/cases}), so the event and the rebuild source can
 * never drift apart. Times are UTC.
 *
 * <p>Schema revision 2 added, at the end and without changing the others:
 *
 * <ul>
 *   <li>{@code openedAt}: when the case was opened;
 *   <li>{@code resolvedAt}: when it reached {@code RESOLVED}; null in every other state, including
 *       the other final states {@code CANCELLED} and {@code CLOSED_AS_UNRECOVERABLE};
 *   <li>{@code trigger}: what opened it ({@link CaseTrigger});
 *   <li>{@code assignedTo}: the token subject of its handler (an opaque id, never a name or an
 *       e-mail address); null while unassigned.
 * </ul>
 *
 * A revision 1 event has none of them: they read as {@code null}, so consumers must tolerate that.
 * {@code reason} is free text and may name people or places: consumers must not index or log it.
 */
public record CaseChangedPayload(
    String caseId,
    String bagTag,
    String legId,
    RecoveryState state,
    long version,
    String reason,
    Instant openedAt,
    Instant resolvedAt,
    CaseTrigger trigger,
    String assignedTo) {}
