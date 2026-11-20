package com.aerotrace.search.elastic;

/** Single indexes owned by the search-service that are not rebuilt by alias swap. */
public enum SupportIndex {
  /**
   * The search-owned leg lookup, {@code _id = legId}: the airport, airline and flight of a leg, so
   * a case event (which carries only the leg id) can be indexed with them.
   */
  LEGS("search-legs", "elasticsearch/legs.json"),
  /**
   * One document per bag, leg and UTC hour in which the leg's risk turned or stayed HIGH: the
   * deterministic ids make the report's "risky bags" an exact count.
   */
  RISK_HOURS("search-risk-hours", "elasticsearch/risk-hours.json"),
  /** Rebuild jobs, the rebuild lock and the idempotency keys of reindex requests. */
  JOBS("search-jobs", "elasticsearch/jobs.json");

  private final String index;
  private final String resource;

  SupportIndex(String index, String resource) {
    this.index = index;
    this.resource = resource;
  }

  public String index() {
    return index;
  }

  public String resource() {
    return resource;
  }
}
