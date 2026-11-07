package com.aerotrace.common.correlation;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.FilterChain;
import java.net.URI;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.HttpMethod;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class CorrelationTest {

  private final CorrelationIdFilter filter = new CorrelationIdFilter();

  @Test
  void reusesValidIncomingHeaderAndClearsMdc() throws Exception {
    var request = new MockHttpServletRequest();
    request.addHeader(CorrelationId.HEADER, "abc-123");
    var response = new MockHttpServletResponse();
    AtomicReference<String> seen = new AtomicReference<>();
    FilterChain chain = (req, res) -> seen.set(MDC.get(CorrelationId.MDC_KEY));

    filter.doFilter(request, response, chain);

    assertThat(seen.get()).isEqualTo("abc-123");
    assertThat(response.getHeader(CorrelationId.HEADER)).isEqualTo("abc-123");
    assertThat(MDC.get(CorrelationId.MDC_KEY)).isNull();
  }

  @Test
  void generatesIdWhenMissing() throws Exception {
    var response = new MockHttpServletResponse();
    filter.doFilter(new MockHttpServletRequest(), response, (req, res) -> {});
    assertThat(response.getHeader(CorrelationId.HEADER)).isNotBlank().hasSizeLessThanOrEqualTo(64);
  }

  @Test
  void replacesUnsafeIncomingValue() throws Exception {
    var request = new MockHttpServletRequest();
    request.addHeader(CorrelationId.HEADER, "bad value with spaces & symbols");
    var response = new MockHttpServletResponse();
    filter.doFilter(request, response, (req, res) -> {});
    assertThat(response.getHeader(CorrelationId.HEADER))
        .matches("[A-Za-z0-9._-]{1,64}")
        .isNotEqualTo("bad value with spaces & symbols");
  }

  @Test
  void interceptorPropagatesMdcValue() throws Exception {
    MDC.put(CorrelationId.MDC_KEY, "prop-1");
    try {
      var request = new MockClientHttpRequest(HttpMethod.GET, URI.create("http://x/y"));
      new CorrelationIdInterceptor().intercept(request, new byte[0], (r, b) -> null);
      assertThat(request.getHeaders().getFirst(CorrelationId.HEADER)).isEqualTo("prop-1");
    } finally {
      MDC.clear();
    }
  }

  @Test
  void interceptorKeepsExistingHeader() throws Exception {
    MDC.put(CorrelationId.MDC_KEY, "prop-1");
    try {
      var request = new MockClientHttpRequest(HttpMethod.GET, URI.create("http://x/y"));
      request.getHeaders().set(CorrelationId.HEADER, "explicit");
      new CorrelationIdInterceptor().intercept(request, new byte[0], (r, b) -> null);
      assertThat(request.getHeaders().get(CorrelationId.HEADER)).containsExactly("explicit");
    } finally {
      MDC.clear();
    }
  }
}
