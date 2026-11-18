package com.aerotrace.recovery.base;

import com.aerotrace.common.event.EventJson;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

/**
 * A stand-in for the capacity-service on a real HTTP port: the recovery-service talks to it with
 * its real client, timeouts and headers. {@code holds} are the reservations it currently keeps.
 */
public final class CapacityStub {

  /** How the next reservation calls are answered. */
  public enum Mode {
    OK,
    FULL,
    ERROR,
    /**
     * Keeps the hold but answers only after {@code delayMillis}: the answer is lost to the caller.
     */
    SLOW
  }

  /** One call the stub received. */
  public record Call(String method, String path, Map<String, String> headers) {}

  private static final String PATH = "/api/v1/capacity/reservations";

  private final HttpServer server;
  public final Map<UUID, Boolean> holds = new ConcurrentHashMap<>();
  public final List<Call> calls = new CopyOnWriteArrayList<>();
  public volatile Mode mode = Mode.OK;
  public volatile boolean releaseFails = false;
  public volatile long delayMillis = 1_500;

  private CapacityStub(HttpServer server) {
    this.server = server;
  }

  public static CapacityStub start() {
    try {
      HttpServer http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      CapacityStub stub = new CapacityStub(http);
      http.createContext(PATH, stub::handle);
      http.setExecutor(Executors.newCachedThreadPool());
      http.start();
      Runtime.getRuntime().addShutdownHook(new Thread(() -> http.stop(0)));
      return stub;
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  public String baseUrl() {
    return "http://127.0.0.1:" + server.getAddress().getPort();
  }

  /** Back to a healthy capacity-service with no holds and no recorded calls. */
  public void reset() {
    mode = Mode.OK;
    releaseFails = false;
    holds.clear();
    calls.clear();
  }

  public long deletes() {
    return calls.stream().filter(c -> c.method().equals("DELETE")).count();
  }

  private void handle(HttpExchange exchange) throws IOException {
    try (exchange) {
      Map<String, String> headers = new ConcurrentHashMap<>();
      exchange
          .getRequestHeaders()
          .forEach(
              (name, values) ->
                  headers.put(name.toLowerCase(java.util.Locale.ROOT), values.get(0)));
      calls.add(new Call(exchange.getRequestMethod(), exchange.getRequestURI().getPath(), headers));
      if (exchange.getRequestMethod().equals("POST")) {
        reserve(exchange);
      } else if (exchange.getRequestMethod().equals("DELETE")) {
        release(exchange);
      } else {
        send(exchange, 405, "");
      }
    }
  }

  private void reserve(HttpExchange exchange) throws IOException {
    String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    UUID id = UUID.fromString(EventJson.mapper().readTree(body).get("reservationId").asString());
    switch (mode) {
      case FULL -> send(exchange, 409, error("CAPACITY_EXCEEDED", 409));
      case ERROR -> send(exchange, 500, error("INTERNAL_ERROR", 500));
      case SLOW -> {
        holds.put(id, true);
        sleep(delayMillis);
        send(exchange, 201, held(id));
      }
      default -> {
        holds.put(id, true);
        send(exchange, 201, held(id));
      }
    }
  }

  private void release(HttpExchange exchange) throws IOException {
    String path = exchange.getRequestURI().getPath();
    UUID id = UUID.fromString(path.substring(path.lastIndexOf('/') + 1));
    if (releaseFails) {
      send(exchange, 500, error("INTERNAL_ERROR", 500));
    } else if (holds.remove(id) != null) {
      send(exchange, 204, "");
    } else {
      send(exchange, 404, error("NOT_FOUND", 404));
    }
  }

  private static String held(UUID id) {
    return "{\"success\":true,\"data\":{\"reservationId\":\""
        + id
        + "\",\"status\":\"HELD\"},\"timestamp\":\""
        + Instant.now()
        + "\"}";
  }

  private static String error(String code, int status) {
    return "{\"code\":\"" + code + "\",\"message\":\"stub\",\"status\":" + status + "}";
  }

  private static void send(HttpExchange exchange, int status, String body) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "application/json");
    if (status == 204 || bytes.length == 0) {
      exchange.sendResponseHeaders(status, -1);
    } else {
      exchange.sendResponseHeaders(status, bytes.length);
      exchange.getResponseBody().write(bytes);
    }
  }

  private static void sleep(long millis) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
