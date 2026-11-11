package com.aerotrace.gateway.support;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Plain HTTP calls to a running test gateway, so tests see exactly what a client would. */
public final class GatewayClient {

  private static final HttpClient HTTP = HttpClient.newHttpClient();
  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final int port;

  public GatewayClient(int port) {
    this.port = port;
  }

  public HttpResponse<String> get(String path, String bearer, String... headers) {
    return send("GET", path, bearer, headers);
  }

  public HttpResponse<String> send(String method, String path, String bearer, String... headers) {
    HttpRequest.Builder request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
            .method(method, HttpRequest.BodyPublishers.noBody());
    if (bearer != null) {
      request.header("Authorization", "Bearer " + bearer);
    }
    for (int i = 0; i + 1 < headers.length; i += 2) {
      request.header(headers[i], headers[i + 1]);
    }
    try {
      return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
    } catch (IOException e) {
      throw new IllegalStateException(e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }

  public static JsonNode json(HttpResponse<String> response) {
    return JSON.readTree(response.body());
  }
}
