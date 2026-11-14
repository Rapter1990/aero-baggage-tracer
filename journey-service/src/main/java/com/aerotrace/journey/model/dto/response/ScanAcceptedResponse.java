package com.aerotrace.journey.model.dto.response;

import java.util.UUID;

/**
 * The answer to a scan: it is accepted and its event is queued for publishing. {@code duplicate} is
 * true when the Idempotency-Key was seen before and the original scan is returned unchanged.
 */
public record ScanAcceptedResponse(
    UUID scanId, String bagTag, String status, boolean lateEvent, boolean duplicate) {

  public static final String QUEUED = "QUEUED";
}
