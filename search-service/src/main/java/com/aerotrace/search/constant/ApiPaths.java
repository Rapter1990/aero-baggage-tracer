package com.aerotrace.search.constant;

/** Every URL path this service answers on, in one place. */
public final class ApiPaths {

  public static final String SEARCH_BAGS = "/api/v1/search/bags";
  public static final String INDEX_STATUS = "/api/v1/search/index/status";

  public static final String REINDEX = "/api/v1/admin/search/reindex";
  public static final String REINDEX_JOB = REINDEX + "/{jobId}";
  public static final String REINDEX_JOB_ALL = REINDEX + "/*";

  public static final String REPORTS = "/api/v1/reports";
  public static final String REPORT_AIRPORT = REPORTS + "/airport/{iata}";
  public static final String REPORTS_ALL = REPORTS + "/**";

  public static final String API_DOCS = "/v3/api-docs";
  public static final String API_DOCS_ALL = "/v3/api-docs/**";
  public static final String API_DOCS_YAML = "/v3/api-docs.yaml";

  public static final String HEALTH = "/actuator/health";
  public static final String HEALTH_ALL = "/actuator/health/**";
  public static final String INFO = "/actuator/info";

  /** The header that makes a reindex request safe to repeat. */
  public static final String IDEMPOTENCY_KEY = "Idempotency-Key";

  /** The header with the shared secret the export endpoints of the owning services accept. */
  public static final String SERVICE_TOKEN = "X-Service-Token";

  private ApiPaths() {}
}
