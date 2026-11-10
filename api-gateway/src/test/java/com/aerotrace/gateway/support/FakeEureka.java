package com.aerotrace.gateway.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A minimal Eureka server over real HTTP that records what the gateway's Eureka client sends:
 * registration (POST), registry fetches (GET), heartbeats (PUT) and de-registration (DELETE).
 */
public final class FakeEureka {

  public record Call(String method, String path, String body) {}

  private static final String EMPTY_REGISTRY =
      "{\"applications\":{\"versions__delta\":\"1\",\"apps__hashcode\":\"\",\"application\":[]}}";

  private static final List<Call> CALLS = new CopyOnWriteArrayList<>();
  private static final HttpServer SERVER;

  static {
    try {
      SERVER = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
      SERVER.createContext("/eureka/", FakeEureka::handle);
      SERVER.start();
    } catch (IOException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  private FakeEureka() {}

  /** The service URL to set as {@code eureka.client.service-url.defaultZone}. */
  public static String serviceUrl() {
    return "http://localhost:" + SERVER.getAddress().getPort() + "/eureka/";
  }

  public static List<Call> calls() {
    return List.copyOf(CALLS);
  }

  private static void handle(HttpExchange exchange) throws IOException {
    String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    String method = exchange.getRequestMethod();
    CALLS.add(new Call(method, exchange.getRequestURI().getPath(), body));

    switch (method) {
      case "GET" -> respond(exchange, 200, EMPTY_REGISTRY);
      case "POST" -> respond(exchange, 204, null);
      default -> respond(exchange, 200, null);
    }
  }

  private static void respond(HttpExchange exchange, int status, String json) throws IOException {
    if (json == null) {
      exchange.sendResponseHeaders(status, -1);
    } else {
      byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().add("Content-Type", "application/json");
      exchange.sendResponseHeaders(status, bytes.length);
      exchange.getResponseBody().write(bytes);
    }
    exchange.close();
  }
}
