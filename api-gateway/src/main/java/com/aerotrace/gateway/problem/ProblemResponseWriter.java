package com.aerotrace.gateway.problem;

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
 * Writes gateway-originated errors (401, 403, 429) in the shared RFC 7807 shape. These happen in
 * servlet filters, before Spring MVC's exception advice can run, so the body is written directly.
 */
@Component
public class ProblemResponseWriter {

  private final JsonMapper json;

  public ProblemResponseWriter(JsonMapper json) {
    this.json = json;
  }

  public void write(HttpServletResponse response, ErrorCode code, String detail)
      throws IOException {
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
