package com.aerotrace.gateway.filter;

import com.aerotrace.common.correlation.CorrelationId;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.Set;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Makes the proxied request carry the correlation id. {@code common-module}'s servlet filter
 * validates or generates the id (and echoes it on the response); this one writes that same id into
 * the request headers, replacing a missing or malformed client value, so the route forwards it.
 */
@Component
public class CorrelationHeaderFilter extends OncePerRequestFilter implements Ordered {

  @Override
  public int getOrder() {
    // Right after common-module's CorrelationIdFilter (HIGHEST_PRECEDENCE).
    return Ordered.HIGHEST_PRECEDENCE + 1;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String id = MDC.get(CorrelationId.MDC_KEY);
    chain.doFilter(id == null ? request : new WithCorrelationId(request, id), response);
  }

  private static final class WithCorrelationId extends HttpServletRequestWrapper {

    private final String id;

    WithCorrelationId(HttpServletRequest request, String id) {
      super(request);
      this.id = id;
    }

    @Override
    public String getHeader(String name) {
      return CorrelationId.HEADER.equalsIgnoreCase(name) ? id : super.getHeader(name);
    }

    @Override
    public Enumeration<String> getHeaders(String name) {
      return CorrelationId.HEADER.equalsIgnoreCase(name)
          ? Collections.enumeration(Set.of(id))
          : super.getHeaders(name);
    }

    @Override
    public Enumeration<String> getHeaderNames() {
      Set<String> names = new LinkedHashSet<>(Collections.list(super.getHeaderNames()));
      names.removeIf(CorrelationId.HEADER::equalsIgnoreCase);
      names.add(CorrelationId.HEADER);
      return Collections.enumeration(names);
    }
  }
}
