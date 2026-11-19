package com.aerotrace.search.reindex;

import com.aerotrace.search.indexer.model.CaseChange;
import com.aerotrace.search.indexer.model.LegSnapshot;
import java.time.Instant;
import java.util.List;

/**
 * The rebuild source: the owning services' own current state, read page by page (keyset paging,
 * {@code after} = the last id of the previous page). Read-only by design: it has no method that
 * could change the source.
 *
 * <p>The real implementation ({@link HttpSourceExportClient}) calls the internal export endpoints
 * that #28 (journey-service, bags) and #29 (recovery-service, cases) add. Until they exist, the
 * tests use an in-memory source.
 */
public interface SourceExportClient {

  /** A page of cases ordered by {@code caseId}, after the given one (null for the first page). */
  ExportPage<CaseChange> cases(String after, int size);

  /** A page of bags ordered by {@code bagTag}, after the given one (null for the first page). */
  ExportPage<BagExport> bags(String after, int size);

  /**
   * One page.
   *
   * @param items the records, in key order
   * @param nextAfter the key to ask the next page after; null when this was the last page
   */
  record ExportPage<T>(List<T> items, String nextAfter) {}

  /** A bag with its itinerary and its latest accepted scan (null when it has none yet). */
  record BagExport(String bagTag, List<LegSnapshot> legs, LatestScan latestScan) {}

  record LatestScan(
      String airport, String checkpoint, String legId, Instant occurredAt, long deviceSequence) {}
}
