package com.aerotrace.search.elastic;

import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import java.io.IOException;
import java.time.Instant;

/** Small helpers around the Elasticsearch client. */
public final class Elastic {

  private Elastic() {}

  /** A call to Elasticsearch that may fail with an I/O error. */
  @FunctionalInterface
  public interface Call<T> {
    T run() throws IOException;
  }

  /**
   * Runs a call for an API request: an unreachable or overloaded cluster becomes {@link
   * SearchUnavailableException} (503); anything else is rethrown as it is.
   */
  public static <T> T call(Call<T> call) {
    try {
      return call.run();
    } catch (IOException e) {
      throw new SearchUnavailableException(e);
    } catch (ElasticsearchException e) {
      if (isTransient(e.status())) {
        throw new SearchUnavailableException(e);
      }
      throw e;
    }
  }

  /** 429 (too many requests) and 5xx may pass; a retry can succeed. */
  public static boolean isTransient(int status) {
    return status == 429 || status >= 500;
  }

  /** Dates are written as ISO-8601 UTC text, so no JSON mapper has to know {@link Instant}. */
  public static String iso(Instant instant) {
    return instant == null ? null : instant.toString();
  }

  /** Reads a date written by {@link #iso}, or an epoch-millis number, or null. */
  public static Instant instant(Object value) {
    if (value == null) {
      return null;
    }
    if (value instanceof Number number) {
      return Instant.ofEpochMilli(number.longValue());
    }
    return Instant.parse(value.toString());
  }
}
