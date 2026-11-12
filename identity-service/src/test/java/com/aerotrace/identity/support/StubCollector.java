package com.aerotrace.identity.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A stand-in for the OpenTelemetry collector: records what is pushed to one OTLP path. The payloads
 * are protobuf, but names, messages and raw id bytes survive as ISO-8859-1 characters.
 */
public final class StubCollector {

  /** One OTLP request: its content type and its body as ISO-8859-1 text. */
  public record Push(String contentType, String body) {}

  private final List<Push> pushes = new CopyOnWriteArrayList<>();
  private final HttpServer server;

  public StubCollector(String path) {
    try {
      server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
      server.createContext(path, this::handle);
      server.start();
    } catch (IOException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  private void handle(HttpExchange exchange) throws IOException {
    pushes.add(
        new Push(
            exchange.getRequestHeaders().getFirst("Content-Type"),
            new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.ISO_8859_1)));
    exchange.sendResponseHeaders(200, -1);
    exchange.close();
  }

  /** Everything pushed so far. */
  public List<Push> pushes() {
    return List.copyOf(pushes);
  }

  public String url(String path) {
    return "http://localhost:" + server.getAddress().getPort() + path;
  }

  /** Waits (polling, never a fixed sleep) for a push whose body contains the marker. */
  public Push awaitPushContaining(String marker) throws InterruptedException {
    long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
    while (System.nanoTime() < deadline) {
      for (Push push : pushes) {
        if (push.body().contains(marker)) {
          return push;
        }
      }
      Thread.sleep(200);
    }
    throw new AssertionError("No OTLP push containing '" + marker + "' within 30 seconds");
  }
}
