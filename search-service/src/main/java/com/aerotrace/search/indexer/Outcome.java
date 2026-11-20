package com.aerotrace.search.indexer;

import java.util.Locale;

/** What indexing one event did; the {@code outcome} tag of {@code search.index.events}. */
public enum Outcome {
  /** The document was written. */
  INDEXED,
  /** An older event than the one already applied; nothing changed (a success, not an error). */
  STALE,
  /** The same version again; nothing changed. */
  DUPLICATE,
  /** The event was dead-lettered. */
  FAILED;

  public String tag() {
    return name().toLowerCase(Locale.ROOT);
  }
}
