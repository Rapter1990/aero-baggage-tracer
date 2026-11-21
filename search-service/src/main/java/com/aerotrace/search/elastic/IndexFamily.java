package com.aerotrace.search.elastic;

/**
 * A read model that is rebuilt by alias swap (ADR 0004, decision 6). Each has versioned indexes
 * ({@code cases-v1}, {@code cases-v1731...}) created from one strict index template, and two
 * aliases: {@code -read} points to exactly one index and serves every search; {@code -live} is the
 * set of indexes the indexer writes to, two of them while a rebuild runs.
 */
public enum IndexFamily {
  CASES("cases"),
  JOURNEYS("journeys");

  private final String base;

  IndexFamily(String base) {
    this.base = base;
  }

  public String base() {
    return base;
  }

  /** The pattern of the index template; aliases never match it. */
  public String pattern() {
    return base + "-v*";
  }

  public String readAlias() {
    return base + "-read";
  }

  public String liveAlias() {
    return base + "-live";
  }

  /** The first index; a fixed name, so two instances starting at once cannot create two. */
  public String initialIndex() {
    return base + "-v1";
  }

  public String templateResource() {
    return "elasticsearch/" + base + ".json";
  }

  /** True when the name is one of this family's indexes. */
  public boolean owns(String index) {
    return index.startsWith(base + "-v");
  }
}
