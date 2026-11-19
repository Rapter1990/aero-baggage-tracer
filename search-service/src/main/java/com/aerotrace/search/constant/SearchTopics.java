package com.aerotrace.search.constant;

import com.aerotrace.common.event.Topics;
import java.util.List;

/**
 * The topics the indexer reads and the event types it accepts.
 *
 * <p>{@link #BAG_REGISTERED} is defined here and not in {@code common-module} {@link Topics}: the
 * journey-service does not publish it yet (#28 adds it, to {@code Topics.ALL}, together with its
 * payload). Until then the listener simply finds the topic empty. Replace this constant with the
 * shared one when #28 lands.
 */
public final class SearchTopics {

  /** Key: bagTag. Producer: journey-service (#28). The itinerary snapshot of a new bag. */
  public static final String BAG_REGISTERED = "baggage.bag.registered.v1";

  public static final String EVENT_TYPE_CASE_CHANGED = "recovery.case.changed";
  public static final String EVENT_TYPE_SCAN_RECEIVED = "baggage.scan.received";
  public static final String EVENT_TYPE_RISK_CHANGED = "baggage.risk.changed";
  public static final String EVENT_TYPE_BAG_REGISTERED = "baggage.bag.registered";
  public static final String EVENT_TYPE_DEAD_LETTER = "baggage.dead-letter";
  public static final int DEAD_LETTER_SCHEMA_VERSION = 1;

  /** Every topic the indexer consumes (and whose consumer-group lag is measured). */
  public static final List<String> CONSUMED =
      List.of(Topics.CASE_CHANGED, Topics.SCAN_RECEIVED, Topics.RISK_CHANGED, BAG_REGISTERED);

  private SearchTopics() {}
}
