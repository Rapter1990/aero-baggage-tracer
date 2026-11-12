package com.aerotrace.identity.docs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.swagger.v3.core.util.Json;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.examples.Example;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MapSchema;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The OpenAPI definition of this service. Every operation that needs a bearer token automatically
 * documents its 401 (no or invalid token) and 403 (valid token, wrong role) answers, so the pair
 * can never be forgotten on a new endpoint.
 */
@Configuration(proxyBeanMethods = false)
class OpenApiConfiguration {

  static final String BEARER = "bearerAuth";

  @Bean
  OpenAPI identityOpenApi() {
    Schema<Object> apiError =
        new Schema<>()
            .type("object")
            .description("The error shape every service returns, with a stable code.")
            .addProperty("code", new StringSchema().example("ACCESS_DENIED"))
            .addProperty("message", new StringSchema().example("Insufficient permissions"))
            .addProperty("status", new Schema<Integer>().type("integer").example(403))
            .addProperty("path", new StringSchema().example("/api/v1/auth/keys/rotate"))
            .addProperty(
                "validationErrors",
                new MapSchema()
                    .additionalProperties(new StringSchema())
                    .description("Field name to rule, for validation failures; otherwise empty."))
            .addProperty("timestamp", new StringSchema().format("date-time"))
            .addProperty("correlationId", new StringSchema().description("Absent when unknown."));
    return new OpenAPI()
        .info(
            new Info()
                .title("AeroTrace Identity Service")
                .version("v1")
                .description(
                    "Issues signed JWT access tokens, publishes the keys to verify them and owns "
                        + "users and roles. Tokens are RS256 or ES256; verify them with the JWKS "
                        + "at /.well-known/jwks.json (several key ids can be live at once). "
                        + "Successful responses are wrapped as {success, data, timestamp}, except "
                        + "the JWKS, whose format is fixed by standard."))
        .components(
            new Components()
                .addSchemas("ApiError", apiError)
                .addSecuritySchemes(
                    BEARER,
                    new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")
                        .description("Access token from POST /api/v1/auth/token")));
  }

  @Bean
  OpenApiCustomizer documentAuthenticationFailures() {
    return openApi ->
        openApi
            .getPaths()
            .forEach(
                (pathName, path) ->
                    path.readOperations()
                        .forEach(
                            operation -> {
                              if (operation.getSecurity() == null
                                  || operation.getSecurity().isEmpty()) {
                                return;
                              }
                              operation
                                  .getResponses()
                                  .addApiResponse(
                                      "401",
                                      error(
                                          "No token, or a token that is invalid, expired or "
                                              + "meant for another audience.",
                                          ApiExamples.UNAUTHENTICATED,
                                          pathName));
                              operation
                                  .getResponses()
                                  .addApiResponse(
                                      "403",
                                      error(
                                          "A valid token whose roles do not allow this call.",
                                          ApiExamples.ACCESS_DENIED,
                                          pathName));
                            }));
  }

  /** The shared example, with the path of the operation it is documented on. */
  private static ApiResponse error(String description, String example, String pathName) {
    try {
      JsonNode parsed = Json.mapper().readTree(example);
      ((ObjectNode) parsed).put("path", pathName);
      return new ApiResponse()
          .description(description)
          .content(
              new Content()
                  .addMediaType(
                      "application/json",
                      new MediaType()
                          .schema(new Schema<>().$ref("#/components/schemas/ApiError"))
                          .addExamples("default", new Example().value(parsed))));
    } catch (java.io.IOException e) {
      throw new IllegalStateException("Invalid OpenAPI example", e);
    }
  }
}
