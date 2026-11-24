package com.aerotrace.notification.docs;

import com.aerotrace.notification.constant.ApiPaths;
import com.aerotrace.notification.constant.DocsConstants;
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

  @Bean
  OpenAPI notificationOpenApi() {
    Schema<Object> apiError =
        new Schema<>()
            .type("object")
            .description("The error shape every service returns, with a stable code.")
            .addProperty("code", new StringSchema().example("NOT_FOUND"))
            .addProperty("message", new StringSchema().example("Notification not found"))
            .addProperty("status", new Schema<Integer>().type("integer").example(404))
            .addProperty("path", new StringSchema().example(ApiPaths.NOTIFICATIONS))
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
                .title("AeroTrace Notification Service")
                .version("v1")
                .description(
                    "In-app notifications for the assigned handler of a recovery case, made from "
                        + "recovery.case.changed.v1: one per (recipient, event, template), however "
                        + "often the event arrives. Each user reads only their own; another "
                        + "user's notification is 404. Successful responses are wrapped as "
                        + "{success, data, timestamp}."))
        .components(
            new Components()
                .addSchemas(DocsConstants.API_ERROR_SCHEMA, apiError)
                .addSecuritySchemes(
                    DocsConstants.BEARER_AUTH,
                    new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")
                        .description(
                            "Access token from identity-service, POST /api/v1/auth/login")));
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
                      org.springframework.http.MediaType.APPLICATION_JSON_VALUE,
                      new MediaType()
                          .schema(new Schema<>().$ref(DocsConstants.API_ERROR_REF))
                          .addExamples("default", new Example().value(parsed))));
    } catch (java.io.IOException e) {
      throw new IllegalStateException("Invalid OpenAPI example", e);
    }
  }
}
