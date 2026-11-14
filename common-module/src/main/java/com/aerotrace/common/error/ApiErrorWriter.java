package com.aerotrace.common.error;

import com.aerotrace.common.api.ApiError;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.http.MediaType;
import tools.jackson.databind.json.JsonMapper;

/**
 * Writes an {@link ApiError} straight to the servlet response. Needed where Spring MVC's exception
 * advice cannot run: servlet filters and security entry points answer 401, 403 and 429 before a
 * controller is involved, and their bodies must look exactly like every other error.
 */
public class ApiErrorWriter {

  private final JsonMapper json;

  public ApiErrorWriter(JsonMapper json) {
    this.json = json;
  }

  public void write(
      HttpServletRequest request, HttpServletResponse response, ErrorCode code, String message)
      throws IOException {
    if (response.isCommitted()) {
      return;
    }
    ApiError error = ApiError.of(code, message, request.getRequestURI());
    response.setStatus(code.httpStatus().value());
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
    response.getOutputStream().write(json.writeValueAsBytes(error));
  }
}
