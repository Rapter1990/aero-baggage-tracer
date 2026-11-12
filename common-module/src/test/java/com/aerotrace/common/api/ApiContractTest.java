package com.aerotrace.common.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The two response envelopes every service shares. */
class ApiContractTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Test
  void anErrorCarriesCodeMessageStatusPathAndTimestamp() {
    ApiError error = ApiError.of("BAG_TAG_DUPLICATE", "Bag already exists", 409, "/api/v1/bags");

    assertThat(error.code()).isEqualTo("BAG_TAG_DUPLICATE");
    assertThat(error.message()).isEqualTo("Bag already exists");
    assertThat(error.status()).isEqualTo(409);
    assertThat(error.path()).isEqualTo("/api/v1/bags");
    assertThat(error.validationErrors()).isEmpty();
    assertThat(error.timestamp()).isBeforeOrEqualTo(Instant.now());
  }

  @Test
  void aValidationErrorIsAlwaysCodedValidationErrorAndListsTheFields() {
    ApiError error =
        ApiError.validation(
            "Request validation failed", 400, "/api/v1/auth/token", Map.of("username", "required"));

    assertThat(error.code()).isEqualTo("VALIDATION_ERROR");
    assertThat(error.validationErrors()).containsEntry("username", "required");
  }

  @Test
  void theCorrelationIdOfTheCurrentRequestIsIncluded() {
    MDC.put("correlationId", "corr-7");
    try {
      assertThat(ApiError.of("X", "m", 400, "/p").correlationId()).isEqualTo("corr-7");
      assertThat(ApiError.validation("m", 400, "/p", Map.of()).correlationId()).isEqualTo("corr-7");
    } finally {
      MDC.clear();
    }
  }

  @Test
  void withoutOneTheFieldIsLeftOutOfTheJson() {
    JsonNode json = JSON.valueToTree(ApiError.of("X", "m", 400, "/p"));

    assertThat(json.propertyNames())
        .containsExactlyInAnyOrder(
            "code", "message", "status", "path", "validationErrors", "timestamp");
    assertThat(json.get("timestamp").asString()).matches("\\d{4}-\\d{2}-\\d{2}T.*Z");
  }

  @Test
  void theCorrelationIdIsInTheJsonWhenPresent() {
    MDC.put("correlationId", "corr-8");
    try {
      JsonNode json = JSON.valueToTree(ApiError.of("X", "m", 400, "/p"));
      assertThat(json.get("correlationId").asString()).isEqualTo("corr-8");
    } finally {
      MDC.clear();
    }
  }

  @Test
  void aSuccessResponseWrapsTheDataWithAFlagAndATimestamp() {
    ApiResponse<List<String>> response = ApiResponse.success(List.of("a"));

    assertThat(response.success()).isTrue();
    assertThat(response.data()).containsExactly("a");
    assertThat(response.timestamp()).isBeforeOrEqualTo(Instant.now());
    JsonNode json = JSON.valueToTree(response);
    assertThat(json.propertyNames()).containsExactlyInAnyOrder("success", "data", "timestamp");
    assertThat(json.get("data").get(0).asString()).isEqualTo("a");
  }
}
