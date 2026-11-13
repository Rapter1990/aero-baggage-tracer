package com.aerotrace.gateway.support;

import java.net.http.HttpResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;

/**
 * Boots the real gateway on a random port against a trusted test key pair and a stub downstream.
 */
@SpringBootTest(
    webEnvironment = WebEnvironment.RANDOM_PORT,
    properties = {
      "eureka.client.enabled=false",
      "aerotrace.gateway.docs.enabled=true",
      "spring.cloud.discovery.enabled=false",
      "aerotrace.gateway.audience=" + TestTokens.AUDIENCE,
      "spring.data.redis.password=unused-in-tests",
      "management.health.redis.enabled=false",
      "management.otlp.metrics.export.enabled=false",
      "management.tracing.export.otlp.enabled=false",
      "management.logging.export.otlp.enabled=false"
    })
@Import(GatewayTestConfig.class)
public abstract class GatewayTestBase {

  @Value("${local.server.port}")
  private int port;

  @DynamicPropertySource
  static void publicKey(DynamicPropertyRegistry registry) {
    registry.add("aerotrace.gateway.public-key", TestTokens::publicKeyPem);
  }

  protected HttpResponse<String> get(String path, String bearer, String... headers) {
    return new GatewayClient(port).get(path, bearer, headers);
  }

  protected HttpResponse<String> send(
      String method, String path, String bearer, String... headers) {
    return new GatewayClient(port).send(method, path, bearer, headers);
  }

  protected static JsonNode json(HttpResponse<String> response) {
    return GatewayClient.json(response);
  }
}
