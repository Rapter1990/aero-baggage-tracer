package com.aerotrace.search.reindex;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aerotrace.common.model.RecoveryState;
import com.aerotrace.search.config.SearchProperties;
import com.aerotrace.search.indexer.model.CaseChange;
import com.aerotrace.search.reindex.SourceExportClient.BagExport;
import com.aerotrace.search.reindex.SourceExportClient.ExportPage;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.mock.env.MockEnvironment;
import tools.jackson.databind.json.JsonMapper;

/**
 * The HTTP client of the export endpoints against a stand-in server: it only sends {@code GET}s,
 * with the service token, pages by {@code after}, and reads the {@code ApiResponse} envelope. The
 * real endpoints are #28 and #29.
 */
class HttpSourceExportClientTest {

  private record Call(String method, String uri, String token) {}

  private HttpServer server;
  private final List<Call> calls = new CopyOnWriteArrayList<>();
  private volatile String casesBody;
  private volatile String bagsBody;
  private volatile int status = 200;

  private static final String EMPTY_PAGE =
      "{\"success\":true,\"data\":{\"items\":[],\"nextAfter\":null}}";

  @BeforeEach
  void start() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/internal/v1/export/cases", exchange -> answer(exchange, casesBody));
    server.createContext("/internal/v1/export/bags", exchange -> answer(exchange, bagsBody));
    server.start();
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  private void answer(HttpExchange exchange, String body) throws IOException {
    try (exchange) {
      calls.add(
          new Call(
              exchange.getRequestMethod(),
              exchange.getRequestURI().toString(),
              exchange.getRequestHeaders().getFirst("X-Service-Token")));
      byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("Content-Type", "application/json");
      exchange.sendResponseHeaders(status, bytes.length);
      exchange.getResponseBody().write(bytes);
    }
  }

  private HttpSourceExportClient client(String token) {
    return client(token, "test", 16_777_216);
  }

  private HttpSourceExportClient client(String token, String profile, int maxPageBytes) {
    String url = "http://127.0.0.1:" + server.getAddress().getPort();
    SearchProperties properties =
        new Binder(
                new MapConfigurationPropertySource(
                    Map.of(
                        "aerotrace.search.export.journey-url", url,
                        "aerotrace.search.export.recovery-url", url,
                        "aerotrace.search.export.service-token", token,
                        "aerotrace.search.export.max-page-bytes", String.valueOf(maxPageBytes))))
            .bindOrCreate("aerotrace.search", SearchProperties.class);
    MockEnvironment environment = new MockEnvironment();
    environment.setActiveProfiles(profile);
    return new HttpSourceExportClient(properties, JsonMapper.builder().build(), environment);
  }

  @Test
  @DisplayName("cases are read with GET, the service token and keyset paging")
  void casesPage() {
    casesBody =
        """
        {"success":true,"data":{"items":[
          {"caseId":"c1","bagTag":"0000000001","legId":"L1","state":"RESOLVED","version":4,
           "reason":"r","openedAt":"2026-01-01T10:00:00Z","resolvedAt":"2026-01-01T11:00:00Z",
           "trigger":"RISK","assignedTo":"a","extra":"ignored"}
        ],"nextAfter":"c1"},"timestamp":"2026-01-01T12:00:00Z"}
        """;

    ExportPage<CaseChange> page = client("secret-token").cases("c0", 50);

    assertThat(page.nextAfter()).isEqualTo("c1");
    assertThat(page.items())
        .singleElement()
        .satisfies(
            c -> {
              assertThat(c.state()).isEqualTo(RecoveryState.RESOLVED);
              assertThat(c.version()).isEqualTo(4);
              assertThat(c.resolvedAt()).isEqualTo(Instant.parse("2026-01-01T11:00:00Z"));
            });
    assertThat(calls)
        .singleElement()
        .isEqualTo(new Call("GET", "/internal/v1/export/cases?size=50&after=c0", "secret-token"));
  }

  @Test
  @DisplayName("without nextAfter a full page continues after its last key and a short one ends")
  void derivedPaging() {
    bagsBody =
        """
        {"success":true,"data":{"items":[
          {"bagTag":"0000000001","legs":[],"latestScan":null},
          {"bagTag":"0000000002","legs":[]}
        ]}}
        """;

    ExportPage<BagExport> full = client("").bags(null, 2);
    ExportPage<BagExport> shortPage = client("").bags(null, 3);

    assertThat(full.nextAfter()).isEqualTo("0000000002");
    assertThat(shortPage.nextAfter()).isNull();
    assertThat(calls).allSatisfy(call -> assertThat(call.method()).isEqualTo("GET"));
    // No token configured: none is sent.
    assertThat(calls).allSatisfy(call -> assertThat(call.token()).isNull());
    assertThat(calls.getFirst().uri()).isEqualTo("/internal/v1/export/bags?size=2");
  }

  @Test
  @DisplayName("an endpoint that is not there yet (404 until #28/#29) fails the call")
  void missingEndpoint() {
    status = 404;
    casesBody = "{\"code\":\"NOT_FOUND\"}";

    assertThatThrownBy(() -> client("t").cases(null, 10))
        .isInstanceOf(SourceExportException.class)
        .hasMessage("export endpoint not found");
  }

  @Test
  @DisplayName("401 and 403 fail with a clear reason: export unauthorized")
  void unauthorized() {
    casesBody = "{}";
    for (int refused : new int[] {401, 403}) {
      status = refused;
      assertThatThrownBy(() -> client("wrong").cases(null, 10))
          .isInstanceOf(SourceExportException.class)
          .hasMessage("export unauthorized");
    }
  }

  @Test
  @DisplayName("outside the development profiles a missing token fails before any call")
  void noTokenOutsideDevelopment() {
    casesBody = EMPTY_PAGE;

    assertThatThrownBy(() -> client("", "default", 1_000).cases(null, 10))
        .isInstanceOf(SourceExportException.class)
        .hasMessageStartingWith("export unauthorized");
    assertThat(calls).isEmpty();
  }

  @Test
  @DisplayName("the after key is encoded strictly: it can never add a query parameter")
  void afterIsEncoded() {
    casesBody = EMPTY_PAGE;

    client("t").cases("a&size=100000+b/c?d#e", 10);

    assertThat(calls.getFirst().uri())
        .isEqualTo("/internal/v1/export/cases?size=10&after=a%26size%3D100000%2Bb%2Fc%3Fd%23e");
  }

  @Test
  @DisplayName("an answer larger than max-page-bytes fails the call")
  void pageTooLarge() {
    casesBody = "{\"data\":{\"items\":[],\"pad\":\"" + "x".repeat(2_000) + "\"}}";

    assertThatThrownBy(() -> client("t", "test", 1_000).cases(null, 10))
        .isInstanceOf(SourceExportException.class)
        .hasMessage("export page larger than the maximum");
  }
}
