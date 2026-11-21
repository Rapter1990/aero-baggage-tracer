package com.aerotrace.common.correlation;

import org.slf4j.MDC;

/**
 * Puts an event's correlation id into the logging context while it is handled, so its log lines,
 * and the events written while handling it, join the trace of the request that caused it. The id is
 * restored, never generated: an event without one (or with an unsafe one) is handled without, and
 * the previous value is put back afterwards.
 *
 * <pre>{@code
 * try (CorrelationScope ignored = CorrelationScope.of(event.correlationId())) {
 *   handle(event);
 * }
 * }</pre>
 */
public final class CorrelationScope implements AutoCloseable {

  private final String previous;

  private CorrelationScope(String correlationId) {
    this.previous = MDC.get(CorrelationId.MDC_KEY);
    if (CorrelationId.isValid(correlationId)) {
      MDC.put(CorrelationId.MDC_KEY, correlationId);
    } else {
      MDC.remove(CorrelationId.MDC_KEY);
    }
  }

  public static CorrelationScope of(String correlationId) {
    return new CorrelationScope(correlationId);
  }

  @Override
  public void close() {
    if (previous == null) {
      MDC.remove(CorrelationId.MDC_KEY);
    } else {
      MDC.put(CorrelationId.MDC_KEY, previous);
    }
  }
}
