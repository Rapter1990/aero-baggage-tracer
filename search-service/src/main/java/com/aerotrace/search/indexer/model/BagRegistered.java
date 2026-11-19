package com.aerotrace.search.indexer.model;

import java.util.List;

/**
 * The payload of {@code baggage.bag.registered.v1} as the search-service expects it from #28: the
 * bag and the snapshot of every leg of its itinerary. The topic is not published yet; when #28
 * defines the shared payload, this reader must match it (unknown fields are ignored, missing ones
 * read as {@code null}).
 */
public record BagRegistered(String bagTag, List<LegSnapshot> legs) {}
