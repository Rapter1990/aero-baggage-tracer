package com.aerotrace.journey.model.dto.response;

import java.util.List;

/**
 * The journey of a bag: the latest accepted state (null before the first scan) and the scan history
 * in event order. {@code historyTruncated} is true when older scans than those listed exist.
 */
public record JourneyResponse(
    String bagTag,
    LatestStateResponse latestState,
    List<LegResponse> itinerary,
    List<ScanHistoryEntry> history,
    boolean historyTruncated) {}
