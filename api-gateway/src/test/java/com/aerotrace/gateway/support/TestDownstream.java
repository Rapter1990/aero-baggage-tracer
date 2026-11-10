package com.aerotrace.gateway.support;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.LinkedHashMap;
import java.util.Map;
import tools.jackson.databind.json.JsonMapper;

/**
 * Stands in for a downstream service: a real HTTP server that answers every request with the
 * headers it received, so tests prove what the gateway actually sent over the wire.
 */
public final class TestDownstream {

  private static final HttpServer SERVER;
  private static final JsonMapper JSON = JsonMapper.builder().build();

  static {
    try {
      SERVER = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
      SERVER.createContext(
          "/",
          exchange -> {
            Map<String, String> seen = new LinkedHashMap<>();
            put(seen, "authorization", exchange.getRequestHeaders().getFirst("Authorization"));
            put(seen, "correlationId", exchange.getRequestHeaders().getFirst("X-Correlation-Id"));
            put(seen, "idempotencyKey", exchange.getRequestHeaders().getFirst("Idempotency-Key"));
            put(seen, "path", exchange.getRequestURI().getPath());
            byte[] body = JSON.writeValueAsBytes(seen);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
          });
      SERVER.start();
    } catch (IOException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  private TestDownstream() {}

  public static String uri() {
    return "http://localhost:" + SERVER.getAddress().getPort();
  }

  private static void put(Map<String, String> map, String key, String value) {
    if (value != null) {
      map.put(key, value);
    }
  }
}
