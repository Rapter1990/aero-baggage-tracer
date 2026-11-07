package com.aerotrace.common.correlation;

import java.io.IOException;
import org.slf4j.MDC;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

/** Propagates the current correlation id on outgoing RestClient / HTTP Interface calls. */
public class CorrelationIdInterceptor implements ClientHttpRequestInterceptor {

  @Override
  public ClientHttpResponse intercept(
      HttpRequest request, byte[] body, ClientHttpRequestExecution execution) throws IOException {
    String id = MDC.get(CorrelationId.MDC_KEY);
    if (id != null && !request.getHeaders().containsHeader(CorrelationId.HEADER)) {
      request.getHeaders().set(CorrelationId.HEADER, id);
    }
    return execution.execute(request, body);
  }
}
