package com.aerotrace.search.reindex;

import com.aerotrace.search.config.SearchProperties;
import com.aerotrace.search.constant.ApiPaths;
import com.aerotrace.search.indexer.model.CaseChange;
import java.net.http.HttpClient;
import java.util.List;
import java.util.function.Function;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads the internal export endpoints of the owning services with plain {@code GET}s and the
 * service token, never through the gateway:
 *
 * <ul>
 *   <li>{@code GET {recovery}/internal/v1/export/cases?after=&size=} (#29)
 *   <li>{@code GET {journey}/internal/v1/export/bags?after=&size=} (#28)
 * </ul>
 *
 * Expected answer: the shared {@code ApiResponse} envelope with {@code data = {items, nextAfter}}
 * ({@code nextAfter} null on the last page). Should an endpoint leave {@code nextAfter} out, a full
 * page continues after its last key and a short page is the last. Unknown fields are ignored. Until
 * #28 and #29 are merged these endpoints answer 404 and a rebuild fails without changing the
 * indexes.
 */
@Component
public class HttpSourceExportClient implements SourceExportClient {

  public static final String CASES_PATH = "/internal/v1/export/cases";
  public static final String BAGS_PATH = "/internal/v1/export/bags";

  private final RestClient recovery;
  private final RestClient journey;
  private final JsonMapper json;

  public HttpSourceExportClient(SearchProperties properties, JsonMapper json) {
    SearchProperties.Export export = properties.export();
    this.json = json;
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
      builder.defaultHeader(ApiPaths.SERVICE_TOKEN, export.serviceToken());
    }
    return builder.build();
  }

  @Override
  public ExportPage<CaseChange> cases(String after, int size) {
    return page(recovery, CASES_PATH, after, size, CaseChange.class, CaseChange::caseId);
  }

  @Override
  public ExportPage<BagExport> bags(String after, int size) {
    return page(journey, BAGS_PATH, after, size, BagExport.class, BagExport::bagTag);
  }

  private <T> ExportPage<T> page(
      RestClient client,
      String path,
      String after,
      int size,
      Class<T> type,
      Function<T, String> key) {
    String body =
        client
            .get()
            .uri(
                builder -> {
                  builder.path(path).queryParam("size", size);
                  if (after != null) {
                    builder.queryParam("after", after);
                  }
                  return builder.build();
                })
            .retrieve()
            .body(String.class);
    JsonNode data = json.readTree(body == null ? "{}" : body).path("data");
    JavaType listType = json.getTypeFactory().constructCollectionType(List.class, type);
    List<T> items =
        data.path("items").isArray() ? json.treeToValue(data.path("items"), listType) : List.of();
    String next;
    if (data.has("nextAfter")) {
      next = data.get("nextAfter").isNull() ? null : data.get("nextAfter").asString();
    } else {
      next = items.size() < size || items.isEmpty() ? null : key.apply(items.getLast());
    }
    return new ExportPage<>(items, next);
  }
}
