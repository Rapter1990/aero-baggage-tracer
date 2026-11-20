package com.aerotrace.search.reindex;

import com.aerotrace.common.event.CaseChangedPayload;
import com.aerotrace.common.export.BagExport;
import com.aerotrace.common.export.ExportPage;

/**
 * The rebuild source: the owning services' own current state, read page by page (keyset paging,
 * {@code after} = the last id of the previous page). Read-only by design: it has no method that
 * could change the source.
 *
 * <p>The real implementation ({@link HttpSourceExportClient}) calls the internal export endpoints
 * of the journey-service (#28, bags) and the recovery-service (#29, cases). The pages and their
 * items are the shared contract types of common-module ({@link ExportPage}, {@link
 * CaseChangedPayload}, {@link BagExport}), the same ones the owners write: one source of truth.
 */
public interface SourceExportClient {

  /** A page of cases ordered by {@code caseId}, after the given one (null for the first page). */
  ExportPage<CaseChangedPayload> cases(String after, int size);

  /** A page of bags ordered by {@code bagTag}, after the given one (null for the first page). */
  ExportPage<BagExport> bags(String after, int size);
}
