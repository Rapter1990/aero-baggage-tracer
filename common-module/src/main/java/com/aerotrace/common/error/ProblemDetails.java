package com.aerotrace.common.error;

import com.aerotrace.common.correlation.CorrelationId;
import java.net.URI;
import java.time.Instant;
import org.slf4j.MDC;
import org.springframework.http.ProblemDetail;

/** Builds the shared RFC 7807 body: type, title, status, detail, errorCode, correlationId. */
public final class ProblemDetails {

  public static final String TYPE_PREFIX = "urn:aerotrace:problem:";

  private ProblemDetails() {}

  public static ProblemDetail of(ErrorCode code, String detail) {
    ProblemDetail pd = ProblemDetail.forStatusAndDetail(code.httpStatus(), detail);
    pd.setTitle(code.title());
    pd.setType(URI.create(TYPE_PREFIX + code.name()));
    pd.setProperty("errorCode", code.name());
    String correlationId = MDC.get(CorrelationId.MDC_KEY);
    if (correlationId != null) {
      pd.setProperty("correlationId", correlationId);
    }
    pd.setProperty("timestamp", Instant.now().toString());
    return pd;
  }
}
