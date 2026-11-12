package com.aerotrace.identity.docs;

import io.swagger.v3.core.util.Json;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.examples.Example;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Content;
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
  static final String PROBLEM_JSON = "application/problem+json";

  @Bean
  OpenAPI identityOpenApi() {
    Schema<Object> problem =
        new Schema<>()
            .type("object")
            .description("RFC 7807 problem details with a stable errorCode.")
            .addProperty("type", new StringSchema().example("urn:aerotrace:problem:ACCESS_DENIED"))
            .addProperty("title", new StringSchema().example("Access denied"))
            .addProperty("status", new Schema<Integer>().type("integer").example(403))
            .addProperty("detail", new StringSchema().example("Insufficient permissions"))
            .addProperty("errorCode", new StringSchema().example("ACCESS_DENIED"))
            .addProperty("correlationId", new StringSchema())
            .addProperty("timestamp", new StringSchema().format("date-time"));
    return new OpenAPI()
        .info(
            new Info()
                .title("AeroTrace Identity Service")
                .version("v1")
                .description(
                    "Issues signed JWT access tokens, publishes the keys to verify them and owns "
                        + "users and roles. Tokens are RS256 or ES256; verify them with the JWKS "
                        + "at /.well-known/jwks.json (several key ids can be live at once)."))
        .components(
            new Components()
                .addSchemas("Problem", problem)
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
            .values()
            .forEach(
                path ->
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
                                      problem(
                                          "No token, or a token that is invalid, expired or "
                                              + "meant for another audience.",
                                          ProblemExamples.UNAUTHENTICATED));
                              operation
                                  .getResponses()
                                  .addApiResponse(
                                      "403",
                                      problem(
                                          "A valid token whose roles do not allow this call.",
                                          ProblemExamples.ACCESS_DENIED));
                            }));
  }

  private static ApiResponse problem(String description, String example) {
    try {
      return new ApiResponse()
          .description(description)
          .content(
              new Content()
                  .addMediaType(
                      PROBLEM_JSON,
                      new MediaType()
                          .schema(new Schema<>().$ref("#/components/schemas/Problem"))
                          .addExamples(
                              "default", new Example().value(Json.mapper().readTree(example)))));
    } catch (java.io.IOException e) {
      throw new IllegalStateException("Invalid OpenAPI example", e);
    }
  }
}
