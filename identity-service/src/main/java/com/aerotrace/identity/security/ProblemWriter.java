package com.aerotrace.identity.security;

import com.aerotrace.common.error.ErrorCode;
import com.aerotrace.common.error.ProblemDetails;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Writes 401 and 403 in the shared RFC 7807 shape. They happen in servlet filters, before Spring
 * MVC's exception advice can run, so the body is written directly.
 */
@Component
class ProblemWriter {

  private final JsonMapper json;

  ProblemWriter(JsonMapper json) {
    this.json = json;
  }

  void write(HttpServletResponse response, ErrorCode code, String detail) throws IOException {
    if (response.isCommitted()) {
      return;
    }
    ProblemDetail problem = ProblemDetails.of(code, detail);
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("type", String.valueOf(problem.getType()));
    body.put("title", problem.getTitle());
    body.put("status", problem.getStatus());
    body.put("detail", problem.getDetail());
    if (problem.getProperties() != null) {
      body.putAll(problem.getProperties());
    }
    response.setStatus(code.httpStatus().value());
    response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
    response.getOutputStream().write(json.writeValueAsBytes(body));
  }
}
