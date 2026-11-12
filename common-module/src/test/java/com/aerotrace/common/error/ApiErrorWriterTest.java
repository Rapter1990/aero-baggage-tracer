package com.aerotrace.common.error;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Servlet filters answer 401, 403 and 429 before MVC's advice can run; this writes the same body.
 */
class ApiErrorWriterTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private final ApiErrorWriter writer = new ApiErrorWriter(JSON);

  @Test
  void writesTheSharedErrorBodyWithTheCodesStatus() throws Exception {
    var request = new MockHttpServletRequest("GET", "/api/v1/auth/me");
    var response = new MockHttpServletResponse();

    writer.write(request, response, ErrorCode.UNAUTHENTICATED, "Authentication required");

    assertThat(response.getStatus()).isEqualTo(401);
    assertThat(response.getContentType()).startsWith("application/json");
    JsonNode body = JSON.readTree(response.getContentAsString());
    assertThat(body.get("code").asString()).isEqualTo("UNAUTHENTICATED");
    assertThat(body.get("message").asString()).isEqualTo("Authentication required");
    assertThat(body.get("status").asInt()).isEqualTo(401);
    assertThat(body.get("path").asString()).isEqualTo("/api/v1/auth/me");
    assertThat(body.get("timestamp").asString()).isNotBlank();
  }

  @Test
  void leavesAnAlreadyCommittedResponseAlone() throws Exception {
    var response = new MockHttpServletResponse();
    response.getWriter().write("partial");
    response.flushBuffer();

    writer.write(new MockHttpServletRequest(), response, ErrorCode.ACCESS_DENIED, "no");

    assertThat(response.getContentAsString()).isEqualTo("partial");
  }
}
