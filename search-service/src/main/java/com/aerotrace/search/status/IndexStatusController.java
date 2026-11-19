package com.aerotrace.search.status;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.aerotrace.common.api.ApiResponse;
import com.aerotrace.search.constant.ApiPaths;
import com.aerotrace.search.elastic.Elastic;
import com.aerotrace.search.elastic.IndexAdmin;
import com.aerotrace.search.elastic.IndexFamily;
import com.aerotrace.search.status.LagMonitor.Lag;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "Index status", description = "Indexing lag and indexes (DISPATCHER, ADMIN)")
class IndexStatusController {

  /**
   * The state of the search indexes.
   *
   * @param lagEvents events not yet indexed; null when Kafka did not answer
   * @param lagSeconds 0 when caught up, else the age of the newest indexed event; null if unknown
   * @param indexedThrough everything published before this is in the indexes
   * @param checkedAt when the lag was measured
   * @param indexes per read model: the read index, the live indexes and the document count
   */
  record IndexStatus(
      Long lagEvents,
      Double lagSeconds,
      Instant indexedThrough,
      Instant checkedAt,
      Map<String, IndexState> indexes) {}

  record IndexState(String read, List<String> live, long documents) {}

  private final LagMonitor lag;
  private final IndexAdmin admin;
  private final ElasticsearchClient client;

  IndexStatusController(LagMonitor lag, IndexAdmin admin, ElasticsearchClient client) {
    this.lag = lag;
    this.admin = admin;
    this.client = client;
  }

  @GetMapping(ApiPaths.INDEX_STATUS)
  @Operation(
      summary = "Indexing lag and indexes",
      description =
          "Measures the consumer-group lag now. While a rebuild runs, live holds two indexes.")
  ApiResponse<IndexStatus> status() {
    Lag now = lag.check();
    Map<String, IndexState> indexes = new LinkedHashMap<>();
    for (IndexFamily family : IndexFamily.values()) {
      indexes.put(
          family.base(),
          Elastic.call(
              () -> {
                String read = admin.readIndex(family);
                return new IndexState(
                    read, admin.liveIndexes(family), client.count(c -> c.index(read)).count());
              }));
    }
    return ApiResponse.success(
        new IndexStatus(
            now.lagEvents(), now.lagSeconds(), now.indexedThrough(), now.checkedAt(), indexes));
  }
}
