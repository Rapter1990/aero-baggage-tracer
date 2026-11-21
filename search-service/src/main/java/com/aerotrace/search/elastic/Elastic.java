package com.aerotrace.search.elastic;

import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import java.io.IOException;
import java.time.Instant;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.elasticsearch.ResourceNotFoundException;

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
    } catch (DataAccessException e) {
      // Spring Data wraps the client's failures (repositories and ElasticsearchOperations).
      if (unavailable(e)) {
        throw new SearchUnavailableException(e);
      }
      throw e;
    }
  }

  /**
   * Whether a Spring Data exception means the cluster cannot serve now: it did not answer, or its
   * answer was a 429 or a 5xx.
   */
  public static boolean unavailable(DataAccessException e) {
    if (e instanceof DataAccessResourceFailureException) {
      return true;
    }
    Integer status = status(e);
    return status != null && isTransient(status);
  }

  /** The HTTP status of the Elasticsearch error behind an exception, or null if there is none. */
  public static Integer status(Throwable e) {
    for (Throwable t = e; t != null; t = t.getCause()) {
      if (t instanceof ElasticsearchException ee) {
        return ee.status();
      }
      if (t instanceof ResourceNotFoundException) {
        return 404;
      }
      if (t instanceof IOException) {
        return 503;
      }
    }
    return null;
  }

  /** 429 (too many requests) and 5xx may pass; a retry can succeed. */
  public static boolean isTransient(int status) {
    return status == 429 || status >= 500;
  }

  /** ISO-8601 UTC text, so no JSON mapper has to know {@link Instant}. */
  public static String iso(Instant instant) {
    return instant == null ? null : instant.toString();
  }

  /**
   * Dates in the documents are epoch milliseconds (the {@code date} mapping accepts them): the
   * Painless guards compare them as numbers, which ISO text of varying precision would not allow.
   */
  public static Long millis(Instant instant) {
    return instant == null ? null : instant.toEpochMilli();
  }

  /** Reads a date written by {@link #millis} or {@link #iso}, or null. */
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
