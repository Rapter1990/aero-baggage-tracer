package com.aerotrace.common.correlation;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

/** Reads or creates the correlation id, exposes it via MDC and echoes it in the response. */
public class CorrelationIdFilter extends OncePerRequestFilter {

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String incoming = request.getHeader(CorrelationId.HEADER);
    String id = CorrelationId.isValid(incoming) ? incoming : CorrelationId.generate();
    MDC.put(CorrelationId.MDC_KEY, id);
    response.setHeader(CorrelationId.HEADER, id);
    try {
      chain.doFilter(request, response);
    } finally {
      MDC.remove(CorrelationId.MDC_KEY);
    }
  }
}
