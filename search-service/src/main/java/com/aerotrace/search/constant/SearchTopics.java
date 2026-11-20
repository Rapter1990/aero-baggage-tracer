package com.aerotrace.search.constant;

import com.aerotrace.common.event.Topics;
import java.util.List;

/** The topics the indexer reads and the event types it accepts (all names from {@link Topics}). */
public final class SearchTopics {

  public static final String EVENT_TYPE_CASE_CHANGED = "recovery.case.changed";
  public static final String EVENT_TYPE_SCAN_RECEIVED = "baggage.scan.received";
  public static final String EVENT_TYPE_RISK_CHANGED = "baggage.risk.changed";
  public static final String EVENT_TYPE_BAG_REGISTERED = "baggage.bag.registered";
  public static final String EVENT_TYPE_DEAD_LETTER = "baggage.dead-letter";
  public static final int DEAD_LETTER_SCHEMA_VERSION = 1;

  /** Every topic the indexer consumes (and whose consumer-group lag is measured). */
  public static final List<String> CONSUMED =
      List.of(
          Topics.CASE_CHANGED, Topics.SCAN_RECEIVED, Topics.RISK_CHANGED, Topics.BAG_REGISTERED);

  private SearchTopics() {}
}
