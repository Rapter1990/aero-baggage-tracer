package com.aerotrace.search.docs;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The OpenAPI definition of this service; every operation needs a bearer token. */
@Configuration(proxyBeanMethods = false)
class OpenApiConfiguration {

  static final String BEARER_AUTH = "bearerAuth";

  @Bean
  OpenAPI searchOpenApi() {
    return new OpenAPI()
        .info(
            new Info()
                .title("AeroTrace Search Service")
                .version("v1")
                .description(
                    "Bag search and the hourly airport report from Elasticsearch read models. "
                        + "Staff only (AGENT, DISPATCHER, ADMIN); PASSENGER is refused. Results "
                        + "are eventually consistent: reports carry indexedThrough and lagSeconds. "
                        + "Successful responses are wrapped as {success, data, timestamp}; errors "
                        + "are the shared ApiError."))
        .addSecurityItem(new SecurityRequirement().addList(BEARER_AUTH))
        .components(
            new Components()
                .addSecuritySchemes(
                    BEARER_AUTH,
                    new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")
                        .description(
                            "Access token from identity-service, POST /api/v1/auth/login")));
  }
}
