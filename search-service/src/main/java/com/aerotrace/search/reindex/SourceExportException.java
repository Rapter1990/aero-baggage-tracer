package com.aerotrace.search.reindex;

/**
 * The export of an owning service cannot be used, for a reason the job shows as it is (a fixed
 * text, never a source record or a secret): for example {@code export unauthorized}.
 */
public class SourceExportException extends RuntimeException {

  public SourceExportException(String reason) {
    super(reason);
  }

  public SourceExportException(String reason, Throwable cause) {
    super(reason, cause);
  }
}
