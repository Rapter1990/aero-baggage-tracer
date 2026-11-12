package com.aerotrace.identity.base;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Boots the real identity-service on a random port against a real PostgreSQL, with every security
 * filter in place, and offers plain HTTP calls to it. Use it for tests that must see what a client
 * really sees: status codes, headers, authentication and authorization.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
public abstract class AbstractIntegrationTest extends AbstractTestContainerConfiguration {

  protected static final String PASSWORD = "correct-horse-battery";

  private static final HttpClient CLIENT = HttpClient.newHttpClient();
  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Value("${local.server.port}")
  private int port;

  protected int port() {
    return port;
  }

  /** A short unique suffix, so tests never collide in the shared database. */
  protected static String unique() {
    return UUID.randomUUID().toString().substring(0, 8);
  }

  protected static JsonNode json(HttpResponse<String> response) {
    return JSON.readTree(response.body());
  }

  /** Any method, with an optional JSON body and bearer token. */
  protected HttpResponse<String> send(String method, String path, String body, String bearer) {
    try {
      HttpRequest.Builder request =
          HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
              .method(
                  method,
                  body == null
                      ? HttpRequest.BodyPublishers.noBody()
                      : HttpRequest.BodyPublishers.ofString(body));
      if (body != null) {
        request.header("Content-Type", "application/json");
      }
      if (bearer != null) {
        request.header("Authorization", "Bearer " + bearer);
      }
      return CLIENT.send(request.build(), HttpResponse.BodyHandlers.ofString());
    } catch (IOException e) {
      throw new IllegalStateException(e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }

  protected HttpResponse<String> get(String path) {
    return send("GET", path, null, null);
  }

  protected HttpResponse<String> postJson(String path, String json) {
    return send("POST", path, json, null);
  }

  protected HttpResponse<String> postJson(String path, String json, String bearer) {
    return send("POST", path, json, bearer);
  }

  /** A body with the given text fields, built with proper JSON quoting. */
  protected static String body(String... namesAndValues) {
    var node = JSON.createObjectNode();
    for (int i = 0; i < namesAndValues.length; i += 2) {
      node.put(namesAndValues[i], namesAndValues[i + 1]);
    }
    return node.toString();
  }

  /** Registers a passenger and returns the username. */
  protected String registerPassenger() {
    String username = "pax-" + unique();
    HttpResponse<String> response =
        postJson(
            "/api/v1/auth/register",
            body(
                "username",
                username,
                "email",
                username + "@example.com",
                "password",
                PASSWORD,
                "fullName",
                "Test Passenger"));
    if (response.statusCode() != 201) {
      throw new IllegalStateException("Registration failed: " + response.statusCode());
    }
    return username;
  }

  /** Signs in and returns the {@code data} object of the answer. */
  protected JsonNode login(String usernameOrEmail, String password) {
    HttpResponse<String> response =
        postJson(
            "/api/v1/auth/login", body("usernameOrEmail", usernameOrEmail, "password", password));
    if (response.statusCode() != 200) {
      throw new IllegalStateException("Login failed: " + response.statusCode());
    }
    return json(response).get("data");
  }
}
