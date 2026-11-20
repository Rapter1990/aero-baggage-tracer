package com.aerotrace.search.reindex;

import com.aerotrace.common.event.CaseChangedPayload;
import com.aerotrace.common.event.EventJson;
import com.aerotrace.common.export.BagExport;
import com.aerotrace.common.export.ExportApi;
import com.aerotrace.common.export.ExportPage;
import com.aerotrace.common.security.ServiceToken;
import com.aerotrace.search.config.Profiles;
import com.aerotrace.search.config.SearchProperties;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.JsonNode;

/**
 * Reads the internal export endpoints of the owning services with plain {@code GET}s and the
 * service token, never through the gateway:
 *
 * <ul>
 *   <li>{@code GET {recovery}/internal/v1/export/cases?after=&size=} (#29)
 *   <li>{@code GET {journey}/internal/v1/export/bags?after=&size=} (#28)
 * </ul>
 *
 * The answer is the shared {@code ApiResponse} envelope with {@code data} an {@link ExportPage}
 * ({@code nextAfter} null on the last page) of the shared items ({@link CaseChangedPayload}, {@link
 * BagExport}), read with the tolerant event reader ({@link EventJson}): unknown fields are ignored
 * and an enum value added by a newer owner (a new {@code trigger}) reads as null. Should an
 * endpoint leave {@code nextAfter} out, a full page continues after its last key and a short page
 * is the last.
 *
 * <p>The {@code after} key is sent as a URI variable, so every reserved character in it is encoded
 * and it can never add or change a query parameter. An answer larger than {@code max-page-bytes} is
 * refused. 401 and 403 fail the job with {@code export unauthorized}; outside the development
 * profiles a missing service token fails it the same way before any call is made.
 */
@Component
public class HttpSourceExportClient implements SourceExportClient {

  static final String UNAUTHORIZED = "export unauthorized";
  static final String NO_TOKEN = UNAUTHORIZED + ": no SEARCH_EXPORT_SERVICE_TOKEN configured";

  private final RestClient recovery;
  private final RestClient journey;
  private final int maxPageBytes;
  private final boolean tokenMissing;

  public HttpSourceExportClient(SearchProperties properties, Environment environment) {
    SearchProperties.Export export = properties.export();
    this.maxPageBytes = export.maxPageBytes();
    boolean noToken = export.serviceToken() == null || export.serviceToken().isBlank();
    this.tokenMissing = noToken && !Profiles.development(environment);
    this.recovery = client(export.recoveryUrl(), export);
    this.journey = client(export.journeyUrl(), export);
  }

  private static RestClient client(String baseUrl, SearchProperties.Export export) {
    HttpClient http =
        HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(export.connectTimeout())
            .build();
    JdkClientHttpRequestFactory requests = new JdkClientHttpRequestFactory(http);
    requests.setReadTimeout(export.readTimeout());
    RestClient.Builder builder = RestClient.builder().baseUrl(baseUrl).requestFactory(requests);
    if (export.serviceToken() != null && !export.serviceToken().isBlank()) {
      builder.defaultHeader(ServiceToken.HEADER, export.serviceToken());
    }
    return builder.build();
  }

  @Override
  public ExportPage<CaseChangedPayload> cases(String after, int size) {
    return page(
        recovery,
        ExportApi.CASES,
        after,
        size,
        CaseChangedPayload.class,
        CaseChangedPayload::caseId);
  }

  @Override
  public ExportPage<BagExport> bags(String after, int size) {
    return page(journey, ExportApi.BAGS, after, size, BagExport.class, BagExport::bagTag);
  }

  private <T> ExportPage<T> page(
      RestClient client,
      String path,
      String after,
      int size,
      Class<T> type,
      Function<T, String> key) {
    if (tokenMissing) {
      throw new SourceExportException(NO_TOKEN);
    }
    Map<String, Object> variables = new HashMap<>();
    variables.put("size", size);
    String body;
    try {
      body =
          client
              .get()
              .uri(
                  builder -> {
                    builder.path(path).queryParam("size", "{size}");
                    if (after != null) {
                      variables.put("after", after);
                      builder.queryParam("after", "{after}");
                    }
                    return builder.build(variables);
                  })
              .exchange((request, response) -> readCapped(response.getStatusCode(), response));
    } catch (RestClientResponseException e) {
      throw failure(e.getStatusCode().value(), e);
    }
    JsonNode data = EventJson.mapper().readTree(body == null ? "{}" : body).path("data");
    JavaType listType =
        EventJson.mapper().getTypeFactory().constructCollectionType(List.class, type);
    List<T> items =
        data.path("items").isArray()
            ? EventJson.mapper().treeToValue(data.path("items"), listType)
            : List.of();
    String next;
    if (data.has("nextAfter")) {
      next = data.get("nextAfter").isNull() ? null : data.get("nextAfter").asString();
    } else {
      next = items.size() < size || items.isEmpty() ? null : key.apply(items.getLast());
    }
    return new ExportPage<>(items, next);
  }

  private String readCapped(HttpStatusCode status, ClientHttpResponse response) throws IOException {
    if (!status.is2xxSuccessful()) {
      throw failure(status.value(), null);
    }
    try (InputStream in = response.getBody()) {
      byte[] bytes = in.readNBytes(maxPageBytes + 1);
      if (bytes.length > maxPageBytes) {
        throw new SourceExportException("export page larger than the maximum");
      }
      return new String(bytes, StandardCharsets.UTF_8);
    }
  }

  private static SourceExportException failure(int status, Throwable cause) {
    if (status == HttpStatus.UNAUTHORIZED.value() || status == HttpStatus.FORBIDDEN.value()) {
      return new SourceExportException(UNAUTHORIZED, cause);
    }
    if (status == HttpStatus.NOT_FOUND.value()) {
      return new SourceExportException("export endpoint not found", cause);
    }
    return new SourceExportException("export failed with status " + status, cause);
  }
}
