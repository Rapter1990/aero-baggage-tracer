package com.aerotrace.search.search;

import com.aerotrace.common.model.RecoveryState;
import com.aerotrace.common.model.RiskLevel;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Request and response shapes of {@code GET /api/v1/search/bags}. */
public final class BagSearchModels {

  private BagSearchModels() {}

  /** The optional, AND-combined filters. */
  public record Criteria(
      String bagTag,
      String airport,
      String airline,
      String flight,
      RiskLevel riskLevel,
      RecoveryState caseState) {

    /** Ties a cursor to the filters it was issued for. */
    public String fingerprint() {
      return String.join(
          "|",
          Objects.toString(bagTag, ""),
          Objects.toString(airport, ""),
          Objects.toString(airline, ""),
          Objects.toString(flight, ""),
          riskLevel == null ? "" : riskLevel.name(),
          caseState == null ? "" : caseState.name());
    }
  }

  /**
   * One page.
   *
   * @param items the bags of this page
   * @param size the page size that was asked for
   * @param nextCursor pass as {@code cursor} for the next page; null on the last page
   * @param hasMore whether there is a next page
   */
  public record Page(List<BagSummary> items, int size, String nextCursor, boolean hasMore) {}

  /** A bag as search shows it; never a raw Elasticsearch hit, never a score. */
  public record BagSummary(
      String bagTag, Instant updatedAt, ScanSummary latestScan, List<LegSummary> legs) {}

  public record ScanSummary(String airport, String checkpoint, Instant occurredAt) {}

  public record LegSummary(
      String legId,
      String flightNumber,
      String airline,
      String origin,
      String destination,
      Instant departureAt,
      String riskLevel,
      String caseId,
      String caseState) {}
}
