package com.aerotrace.search.reindex;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.aerotrace.common.event.CaseChangedPayload;
import com.aerotrace.common.model.CaseTrigger;
import com.aerotrace.common.model.RecoveryState;
import com.aerotrace.common.security.Role;
import com.aerotrace.search.base.AbstractSearchTest;
import com.aerotrace.search.base.Events;
import com.aerotrace.search.base.InMemorySource;
import com.aerotrace.search.elastic.IndexAdmin;
import com.aerotrace.search.elastic.IndexFamily;
import com.aerotrace.search.indexer.EventIndexer;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * AT-18 "Rebuild Elasticsearch from source data with no duplicate documents", for the cases read
 * model, against a real Elasticsearch.
 *
 * <p>The source is {@link InMemorySource}, a stand-in for the recovery-service's export endpoint
 * (#29) that a test can steer (a race at a chosen page, a failing or misbehaving page). So this
 * proves the search-service's side: paging through more than one export page, external versions,
 * the dual-alias swap, a racing stale event, a second run, and that the rebuild only read the
 * source. That the real recovery-service exports every case is proven by its own tests and, end to
 * end, by a rebuild against the real owners.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(InMemorySource.Config.class)
class RebuildFromSourceTest extends AbstractSearchTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final int N = 150; // more than one export page (100 in the test profile)

  @Autowired MockMvc mockMvc;
  @Autowired InMemorySource source;
  @Autowired EventIndexer indexer;
  @Autowired IndexAdmin admin;
  @Autowired ElasticsearchClient client;

  // Seeded: the states and versions of the source are the same on every run.
  private final Random random = new Random(18);

  @BeforeEach
  void freshSource() {
    awaitNoLiveRebuildLock(client);
    source.reset();
  }

  private Map<String, CaseChangedPayload> seed(int count) {
    String run = UUID.randomUUID().toString().substring(0, 6);
    RecoveryState[] states = RecoveryState.values();
    for (int i = 0; i < count; i++) {
      String caseId = "at18-" + run + "-" + String.format("%04d", i);
      Instant opened = Instant.parse("2026-04-01T00:00:00Z").plusSeconds(i * 60L);
      RecoveryState state = states[random.nextInt(states.length)];
      source.cases.put(
          caseId,
          new CaseChangedPayload(
              caseId,
              newBagTag(),
              newId("leg"),
              state,
              random.nextInt(20),
              "seeded",
              opened,
              state == RecoveryState.RESOLVED ? opened.plusSeconds(1800) : null,
              CaseTrigger.RISK,
              "agent-1"));
    }
    return new HashMap<>(source.cases);
  }

  private String start(String key, String scope) throws Exception {
    String body =
        mockMvc
            .perform(
                post("/api/v1/admin/search/reindex")
                    .param("scope", scope)
                    .header("Idempotency-Key", key)
                    .with(as(Role.ADMIN)))
            .andExpect(status().isAccepted())
            .andExpect(header().exists("Location"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    return JSON.readTree(body).get("data").get("jobId").asString();
  }

  private JsonNode job(String jobId) throws Exception {
    String body =
        mockMvc
            .perform(get("/api/v1/admin/search/reindex/{id}", jobId).with(as(Role.ADMIN)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return JSON.readTree(body).get("data");
  }

  private JsonNode finished(String jobId) throws Exception {
    await()
        .atMost(Duration.ofSeconds(120))
        .until(() -> !"RUNNING".equals(job(jobId).get("status").asString()));
    return job(jobId);
  }

  /** Every hit of the read alias: an id appearing twice would mean two indexes are read. */
  @SuppressWarnings("rawtypes")
  private List<Hit<Map>> everything() throws Exception {
    client.indices().refresh(r -> r.index(IndexFamily.CASES.readAlias()));
    SearchResponse<Map> all =
        client.search(
            s -> s.index(IndexFamily.CASES.readAlias()).size(10_000).version(true), Map.class);
    return all.hits().hits();
  }

  @SuppressWarnings("rawtypes")
  private void assertIndexEqualsSource(
      Map<String, CaseChangedPayload> expected, Map<String, Long> newer) throws Exception {
    List<Hit<Map>> hits = everything();
    List<String> ids = hits.stream().map(Hit::id).toList();
    // _count == N, the ids are the source's ids, and no id appears twice.
    assertThat(client.count(c -> c.index(IndexFamily.CASES.readAlias())).count()).isEqualTo(N);
    assertThat(ids).hasSize(N).doesNotHaveDuplicates();
    assertThat(new HashSet<>(ids)).isEqualTo(expected.keySet());
    var duplicates =
        client.search(
            s ->
                s.index(IndexFamily.CASES.readAlias())
                    .size(0)
                    .aggregations(
                        "dup", a -> a.terms(t -> t.field("docId").minDocCount(2).size(10))),
            Map.class);
    assertThat(duplicates.aggregations().get("dup").sterms().buckets().array()).isEmpty();
    // Stored versions equal source versions (a live event that is newer wins).
    for (Hit<Map> hit : hits) {
      long want = newer.getOrDefault(hit.id(), expected.get(hit.id()).version());
      assertThat(hit.version()).as("version of %s", hit.id()).isEqualTo(want);
    }
    // _alias/cases-read names exactly one index.
    assertThat(admin.indexesOf(IndexFamily.CASES.readAlias())).hasSize(1);
  }

  @Test
  @DisplayName(
      "AT-18 rebuild cases from the source: count, ids and versions equal the source, no"
          + " duplicates, one read index, a racing stale event loses, a second run is the same,"
          + " and the source is only read")
  void at18RebuildWithNoDuplicates() throws Exception {
    // Left-overs that are not in the source (or older) must not survive the rebuild.
    String orphan = newId("orphan");
    indexer.indexCases(
        List.of(
            Events.caseRecord(
                Events.caseV1(orphan, newBagTag(), newId("leg"), RecoveryState.OPEN, 1))));
    Map<String, CaseChangedPayload> expected = seed(N);
    String before = admin.readIndex(IndexFamily.CASES);

    // Both targets are on the first export page, which is already loaded when they race.
    List<String> ids = new ArrayList<>(new java.util.TreeSet<>(expected.keySet()));
    CaseChangedPayload staleTarget = expected.get(ids.get(10));
    CaseChangedPayload newerTarget = expected.get(ids.get(50));
    List<Integer> searchStatuses = new ArrayList<>();
    List<String> readDuringBuild = new ArrayList<>();
    source.beforeCasePage =
        page -> {
          if (page != 2) {
            return;
          }
          try {
            // The owner moves one exported case on by itself (its own write, not the rebuild's)
            // and publishes it; and a stale event (one version older) of another one arrives late.
            CaseChangedPayload moved = withVersion(newerTarget, newerTarget.version() + 1);
            source.cases.put(moved.caseId(), moved);
            indexer.indexCases(
                List.of(
                    Events.caseRecord(
                        Events.caseV2(
                            withVersion(staleTarget, staleTarget.version() - 1),
                            Instant.now(),
                            "race-stale")),
                    Events.caseRecord(
                        Events.caseV2(
                            withVersion(newerTarget, newerTarget.version() + 1),
                            Instant.now(),
                            "race-newer"))));
            // Searches keep working, still on the old index.
            searchStatuses.add(
                mockMvc
                    .perform(get("/api/v1/search/bags").with(as(Role.AGENT)))
                    .andReturn()
                    .getResponse()
                    .getStatus());
            readDuringBuild.add(admin.readIndex(IndexFamily.CASES));
          } catch (Exception e) {
            throw new IllegalStateException(e);
          }
        };

    String key = UUID.randomUUID().toString();
    String jobId = start(key, "CASES");
    JsonNode job = finished(jobId);

    assertThat(job.get("status").asString()).isEqualTo("SUCCEEDED");
    assertThat(searchStatuses).containsExactly(200);
    assertThat(readDuringBuild).containsExactly(before);
    assertThat(admin.readIndex(IndexFamily.CASES)).isNotEqualTo(before);
    assertThat(admin.liveIndexes(IndexFamily.CASES))
        .containsExactly(admin.readIndex(IndexFamily.CASES));
    JsonNode results = job.get("results").get("cases");
    assertThat(results.get("sourceCount").asLong()).isEqualTo(N);
    assertThat(results.get("indexedCount").asLong()).isEqualTo(N);
    assertThat(results.get("missing").asLong()).isZero();
    // The live event of the moved case is newer than its exported page: the newest version wins;
    // the stale event lost (its case is at the exported version).
    Map<String, CaseChangedPayload> current = new HashMap<>(source.cases);
    assertIndexEqualsSource(current, Map.of());
    assertThat(client.exists(e -> e.index(IndexFamily.CASES.readAlias()).id(orphan)).value())
        .isFalse();

    // The rebuild itself changes nothing in the source.
    long checksumAfterFirst = source.checksum();

    // The same key again is the same job, and nothing runs again.
    int callsAfterFirst = source.calls.size();
    assertThat(start(key, "CASES")).isEqualTo(jobId);
    assertThat(source.calls).hasSize(callsAfterFirst);

    // A second run gives the same result.
    source.beforeCasePage = page -> {};
    JsonNode second = finished(start(UUID.randomUUID().toString(), "CASES"));
    assertThat(second.get("status").asString()).isEqualTo("SUCCEEDED");
    assertThat(second.get("results").get("cases").get("sourceChecksum").asString())
        .isEqualTo(second.get("results").get("cases").get("indexChecksum").asString());
    assertIndexEqualsSource(current, Map.of());

    // The source was only read: pages of the export, nothing else, and it is unchanged by both
    // runs (the one change above was the owner's own, before the first run ended).
    assertThat(source.calls).allSatisfy(call -> assertThat(call).startsWith("GET cases"));
    assertThat(source.calls.size()).isEqualTo(4); // 2 pages per run
    assertThat(source.checksum()).isEqualTo(checksumAfterFirst);
    assertThat(source.cases).isEqualTo(current);
  }

  private static CaseChangedPayload withVersion(CaseChangedPayload c, long version) {
    return new CaseChangedPayload(
        c.caseId(),
        c.bagTag(),
        c.legId(),
        c.state(),
        Math.max(version, 0),
        c.reason(),
        c.openedAt(),
        c.resolvedAt(),
        c.trigger(),
        c.assignedTo());
  }

  @Test
  @DisplayName(
      "while a rebuild runs another one is 409 REINDEX_IN_PROGRESS, and the same key gets the"
          + " same job")
  void oneRebuildAtATime() throws Exception {
    seed(5);
    CountDownLatch gate = new CountDownLatch(1);
    source.gate = gate;
    String key = UUID.randomUUID().toString();
    String jobId = start(key, "CASES");
    try {
      mockMvc
          .perform(
              post("/api/v1/admin/search/reindex")
                  .header("Idempotency-Key", UUID.randomUUID().toString())
                  .with(as(Role.ADMIN)))
          .andExpect(status().isConflict())
          .andExpect(jsonPath("$.code").value("REINDEX_IN_PROGRESS"));
      assertThat(start(key, "CASES")).isEqualTo(jobId);
      assertThat(job(jobId).get("status").asString()).isEqualTo("RUNNING");
    } finally {
      gate.countDown();
    }
    assertThat(finished(jobId).get("status").asString()).isEqualTo("SUCCEEDED");
  }

  @Test
  @DisplayName(
      "a rebuild that fails half-way leaves the read alias where it was and discards"
          + " the new index")
  void failedRebuildChangesNothing() throws Exception {
    seed(N);
    source.failOnCasePage = 2;
    String before = admin.readIndex(IndexFamily.CASES);

    JsonNode job = finished(start(UUID.randomUUID().toString(), "CASES"));

    assertThat(job.get("status").asString()).isEqualTo("FAILED");
    assertThat(job.get("error").asString()).isEqualTo("failed: IllegalStateException");
    assertThat(admin.readIndex(IndexFamily.CASES)).isEqualTo(before);
    assertThat(admin.liveIndexes(IndexFamily.CASES)).containsExactly(before);
  }

  @Test
  @DisplayName(
      "the read alias moves only when the counts match: a source that repeats a case"
          + " across pages fails the check")
  void countsMustMatch() throws Exception {
    seed(N);
    source.repeatAcrossPages = true;
    String before = admin.readIndex(IndexFamily.CASES);

    JsonNode job = finished(start(UUID.randomUUID().toString(), "CASES"));

    assertThat(job.get("status").asString()).isEqualTo("FAILED");
    assertThat(job.get("error").asString()).contains("fewer documents");
    assertThat(admin.readIndex(IndexFamily.CASES)).isEqualTo(before);
  }

  @Test
  @DisplayName("a reindex request needs an Idempotency-Key; an unknown job is 404")
  void requestValidation() throws Exception {
    mockMvc
        .perform(post("/api/v1/admin/search/reindex").with(as(Role.ADMIN)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    mockMvc
        .perform(
            post("/api/v1/admin/search/reindex")
                .param("scope", "EVERYTHING")
                .header("Idempotency-Key", "k")
                .with(as(Role.ADMIN)))
        .andExpect(status().isBadRequest());
    mockMvc
        .perform(get("/api/v1/admin/search/reindex/{id}", UUID.randomUUID()).with(as(Role.ADMIN)))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("NOT_FOUND"));
  }
}
