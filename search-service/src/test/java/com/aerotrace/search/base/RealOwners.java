package com.aerotrace.search.base;

import com.aerotrace.common.event.EventJson;
import com.aerotrace.common.security.JwtClaims;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;

/**
 * The real owning services for an end-to-end rebuild test: the packaged journey-service and
 * recovery-service jars (the same artifacts the Docker images run), started as their own processes
 * on one real PostgreSQL (schemas {@code journey} and {@code recovery}, as in Compose) and the test
 * Kafka, with Eureka, the config server and OTLP off. Tokens are signed with a key pair made for
 * the run; the owners verify them with its public key. The recovery-service talks to a capacity
 * stand-in on a real port (it is not the subject here); its Redis is unreachable on purpose (the
 * policy is then read from the database) and its Kafka consumers are off, so nothing but this test
 * changes the source.
 *
 * <p>The jars must have been built ({@code mvn package} of the two modules; the reactor builds them
 * before the search-service). Without them the test is skipped locally and fails in CI.
 */
public final class RealOwners implements AutoCloseable {

  /** The secret both owners accept on {@code /internal/**} and the search-service sends. */
  public static final String SERVICE_TOKEN = "e2e-export-token-" + UUID.randomUUID();

  private static final Path ROOT = Path.of("..").toAbsolutePath().normalize();
  private static final String VERSION = "0.0.1-SNAPSHOT";
  private static final Duration START_TIMEOUT = Duration.ofMinutes(4);

  private final PostgreSQLContainer postgres;
  private final HttpServer capacity;
  private final KeyPair keys;
  private final Owner journey;
  private final Owner recovery;
  private final HttpClient http = HttpClient.newHttpClient();

  private record Owner(String name, Process process, int port, Path log) {
    String url() {
      return "http://127.0.0.1:" + port;
    }
  }

  /** The executable jar a module's build produced, or null when it has not been built. */
  public static Path jar(String module) {
    Path jar = ROOT.resolve(module).resolve("target").resolve(module + "-" + VERSION + "-exec.jar");
    return Files.isRegularFile(jar) ? jar : null;
  }

  public static boolean available() {
    return jar("journey-service") != null && jar("recovery-service") != null;
  }

  private RealOwners(String kafkaBootstrapServers) throws Exception {
    postgres = new PostgreSQLContainer("postgres:18-alpine");
    postgres.start();
    capacity = capacityStandIn();
    KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(2048);
    keys = generator.generateKeyPair();
    Path work = Files.createTempDirectory("aerotrace-e2e");
    journey =
        start(
            "journey-service",
            work,
            kafkaBootstrapServers,
            List.of("--aerotrace.journey.export.service-token=" + SERVICE_TOKEN));
    recovery =
        start(
            "recovery-service",
            work,
            kafkaBootstrapServers,
            List.of(
                "--aerotrace.recovery.export.service-token=" + SERVICE_TOKEN,
                "--aerotrace.recovery.consumers.enabled=false",
                "--aerotrace.recovery.capacity.base-url=http://127.0.0.1:"
                    + capacity.getAddress().getPort(),
                "--spring.data.redis.host=127.0.0.1",
                "--spring.data.redis.port=" + freePort(),
                "--spring.data.redis.password=unused"));
    awaitHealthy(journey);
    awaitHealthy(recovery);
  }

  public static RealOwners start(String kafkaBootstrapServers) throws Exception {
    return new RealOwners(kafkaBootstrapServers);
  }

  public String journeyUrl() {
    return journey.url();
  }

  public String recoveryUrl() {
    return recovery.url();
  }

  // ------------------------------------------------------------------ processes

  private Owner start(String module, Path work, String kafka, List<String> extra)
      throws IOException {
    int port = freePort();
    List<String> command = new ArrayList<>();
    command.add(ProcessHandle.current().info().command().orElse("java"));
    command.add("-Xmx384m");
    command.add("-jar");
    command.add(jar(module).toString());
    command.add("--server.port=" + port);
    command.add("--spring.datasource.url=" + postgres.getJdbcUrl());
    command.add("--spring.datasource.username=" + postgres.getUsername());
    command.add("--spring.datasource.password=" + postgres.getPassword());
    command.add("--spring.datasource.hikari.maximum-pool-size=5");
    command.add("--spring.kafka.bootstrap-servers=" + kafka);
    command.add("--eureka.client.enabled=false");
    command.add("--spring.cloud.config.enabled=false");
    command.add("--spring.cloud.discovery.enabled=false");
    command.add("--management.otlp.metrics.export.enabled=false");
    command.add("--management.tracing.export.otlp.enabled=false");
    command.add("--management.logging.export.otlp.enabled=false");
    command.add("--aerotrace." + module.replace("-service", "") + ".docs.enabled=false");
    if ("journey-service".equals(module)) {
      // Whatever JOURNEY_LIQUIBASE_CONTEXTS the developer has exported: no sample data (ADR 0007).
      command.add("--spring.liquibase.contexts=expand,contract");
    }
    command.addAll(extra);
    Path log = Path.of("target", "e2e-" + module + ".log").toAbsolutePath();
    Files.createDirectories(log.getParent());
    ProcessBuilder builder =
        new ProcessBuilder(command)
            // Not the repository: no developer .env is picked up.
            .directory(work.toFile())
            .redirectErrorStream(true)
            .redirectOutput(log.toFile());
    builder.environment().put("IDENTITY_JWT_PUBLIC_KEY", publicKeyPem());
    builder.environment().put("EUREKA_PASSWORD", "unused");
    Process process = builder.start();
    Runtime.getRuntime().addShutdownHook(new Thread(process::destroyForcibly));
    return new Owner(module, process, port, log);
  }

  private void awaitHealthy(Owner owner) throws Exception {
    Instant deadline = Instant.now().plus(START_TIMEOUT);
    while (Instant.now().isBefore(deadline)) {
      if (!owner.process().isAlive()) {
        throw new IllegalStateException(owner.name() + " stopped: " + tail(owner.log()));
      }
      try {
        HttpResponse<String> health =
            http.send(
                HttpRequest.newBuilder(URI.create(owner.url() + "/actuator/health"))
                    .timeout(Duration.ofSeconds(2))
                    .build(),
                HttpResponse.BodyHandlers.ofString());
        if (health.statusCode() == 200) {
          return;
        }
      } catch (IOException notYet) {
        // Still starting.
      }
      Thread.sleep(500);
    }
    throw new IllegalStateException(owner.name() + " did not start: " + tail(owner.log()));
  }

  private static String tail(Path log) {
    try {
      List<String> lines = Files.readAllLines(log, StandardCharsets.UTF_8);
      return String.join("\n", lines.subList(Math.max(0, lines.size() - 40), lines.size()));
    } catch (IOException e) {
      return "(no log)";
    }
  }

  private static int freePort() throws IOException {
    try (ServerSocket socket = new ServerSocket(0)) {
      return socket.getLocalPort();
    }
  }

  @Override
  public void close() {
    for (Owner owner : List.of(journey, recovery)) {
      owner.process().destroy();
    }
    capacity.stop(0);
    postgres.stop();
  }

  // ------------------------------------------------------------------ calls, as staff

  private String publicKeyPem() {
    String body =
        Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
            .encodeToString(keys.getPublic().getEncoded());
    return "-----BEGIN PUBLIC KEY-----\n" + body + "\n-----END PUBLIC KEY-----\n";
  }

  /** A token as the identity-service issues it, for the given role. */
  public String token(String role) {
    try {
      JWTClaimsSet claims =
          new JWTClaimsSet.Builder()
              .subject("e2e-" + role.toLowerCase(java.util.Locale.ROOT))
              .issuer(JwtClaims.ISSUER)
              .audience("aerotrace-api")
              .claim(JwtClaims.ROLES, List.of(role))
              .issueTime(Date.from(Instant.now().minusSeconds(30)))
              .expirationTime(Date.from(Instant.now().plusSeconds(3600)))
              .build();
      SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims);
      jwt.sign(new RSASSASigner((RSAPrivateKey) keys.getPrivate()));
      return jwt.serialize();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  /** A JSON call to an owner's public API as the given role; answers status and body. */
  public JsonNode call(
      String owner,
      String method,
      String path,
      Object body,
      String role,
      Map<String, String> headers,
      int expectedStatus) {
    String base = owner.equals("journey") ? journey.url() : recovery.url();
    HttpRequest.Builder request =
        HttpRequest.newBuilder(URI.create(base + path))
            .timeout(Duration.ofSeconds(30))
            .header("Authorization", "Bearer " + token(role))
            .header("Content-Type", "application/json")
            .method(
                method,
                body == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(
                        EventJson.mapper().writeValueAsString(body)));
    headers.forEach(request::header);
    try {
      HttpResponse<String> response =
          http.send(request.build(), HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() != expectedStatus) {
        throw new IllegalStateException(
            method + " " + path + " answered " + response.statusCode() + ": " + response.body());
      }
      return response.body().isEmpty()
          ? null
          : EventJson.mapper().readTree(response.body()).path("data");
    } catch (IOException e) {
      throw new IllegalStateException(e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }

  /** A raw GET of an owner, with optional headers, for the token checks. */
  public int status(String url, Map<String, String> headers) throws Exception {
    HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url)).GET();
    headers.forEach(request::header);
    return http.send(request.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
  }

  // ------------------------------------------------------------------ the owners' databases

  /** Runs a read-only query on the owners' database (the test's oracle; never the rebuild's). */
  public <T> List<T> query(String sql, Row<T> row) throws SQLException {
    try (Connection connection =
            DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        Statement statement = connection.createStatement()) {
      connection.setReadOnly(true);
      List<T> rows = new ArrayList<>();
      try (ResultSet result = statement.executeQuery(sql)) {
        while (result.next()) {
          rows.add(row.map(result));
        }
      }
      return rows;
    }
  }

  @FunctionalInterface
  public interface Row<T> {
    T map(ResultSet row) throws SQLException;
  }

  /**
   * Row count and an order-independent checksum of every table of the schema, optionally leaving
   * out the rows that match a condition per table.
   */
  public Map<String, String> fingerprint(String schema, Map<String, String> excluding)
      throws SQLException {
    List<String> tables =
        query(
            "SELECT table_name FROM information_schema.tables WHERE table_schema = '"
                + schema
                + "' AND table_type = 'BASE TABLE' ORDER BY table_name",
            r -> r.getString(1));
    Map<String, String> fingerprint = new LinkedHashMap<>();
    for (String table : tables) {
      String where =
          excluding.containsKey(table) ? " WHERE NOT (" + excluding.get(table) + ")" : "";
      fingerprint.put(
          table,
          query(
                  "SELECT count(*) || ':' || coalesce(sum(hashtext(t::text)::bigint), 0) FROM "
                      + schema
                      + "."
                      + table
                      + " t"
                      + where,
                  r -> r.getString(1))
              .getFirst());
    }
    return fingerprint;
  }

  /** Waits until both outboxes have published everything (their relays run every 200 ms). */
  public void awaitOutboxesDrained() throws Exception {
    Instant deadline = Instant.now().plusSeconds(60);
    while (Instant.now().isBefore(deadline)) {
      long pending =
          query(
                  "SELECT (SELECT count(*) FROM journey.outbox_event WHERE published_at IS NULL"
                      + " AND parked_at IS NULL) + (SELECT count(*) FROM recovery.outbox_event"
                      + " WHERE published_at IS NULL AND parked_at IS NULL)",
                  r -> r.getLong(1))
              .getFirst();
      if (pending == 0) {
        return;
      }
      Thread.sleep(200);
    }
    throw new IllegalStateException("the owners' outboxes did not drain");
  }

  // ------------------------------------------------------------------ capacity stand-in

  /** Holds, confirms and releases anything: moving a case to RESERVED needs an answer. */
  private static HttpServer capacityStandIn() throws IOException {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/api/v1/capacity/reservations",
        exchange -> {
          try (exchange) {
            if (exchange.getRequestMethod().equals("POST")) {
              String body =
                  new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
              String id = EventJson.mapper().readTree(body).get("reservationId").asString();
              answer(
                  exchange,
                  201,
                  "{\"success\":true,\"data\":{\"reservationId\":\""
                      + id
                      + "\",\"status\":\"HELD\"},\"timestamp\":\""
                      + Instant.now()
                      + "\"}");
            } else {
              answer(exchange, 204, "");
            }
          }
        });
    server.setExecutor(Executors.newCachedThreadPool());
    server.start();
    return server;
  }

  private static void answer(HttpExchange exchange, int status, String body) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "application/json");
    exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
    if (bytes.length > 0) {
      exchange.getResponseBody().write(bytes);
    }
  }
}
