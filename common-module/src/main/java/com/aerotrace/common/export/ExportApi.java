package com.aerotrace.common.export;

/**
 * The internal export endpoints the owning services offer so the search-service can rebuild its
 * read models without reading their databases (ADR 0004, decision 1). Read-only, keyset-paged by
 * the record's own key ({@code ?after=<last key>&size=}), authenticated only by the service token
 * ({@code com.aerotrace.common.security.ServiceToken}), and never routed by the gateway.
 *
 * <p>The answer is {@code ApiResponse<ExportPage<T>>}: {@code T} is {@code CaseChangedPayload} for
 * cases and {@link BagExport} for bags.
 */
public final class ExportApi {

  /** Everything internal: its own security chain, no user token, no gateway route. */
  public static final String INTERNAL_ALL = "/internal/**";

  /** recovery-service: cases ordered by {@code caseId}. */
  public static final String CASES = "/internal/v1/export/cases";

  /** journey-service: bags ordered by {@code bagTag}. */
  public static final String BAGS = "/internal/v1/export/bags";

  public static final String AFTER = "after";
  public static final String SIZE = "size";

  public static final int DEFAULT_SIZE = 100;

  /** The largest page an export answers; a larger {@code size} is {@code 400}. */
  public static final int MAX_SIZE = 500;

  public static final String SIZE_RULE = "size must be between 1 and " + MAX_SIZE;

  private ExportApi() {}
}
