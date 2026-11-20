package com.aerotrace.search.reindex;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.aerotrace.common.event.CaseChangedPayload;
import com.aerotrace.common.event.LegSnapshot;
import com.aerotrace.common.export.BagExport;
import com.aerotrace.common.model.CaseTrigger;
import com.aerotrace.common.model.RecoveryState;
import com.aerotrace.common.security.JwtClaims;
import com.aerotrace.common.security.Role;
import com.aerotrace.search.base.AbstractSearchTest;
import com.aerotrace.search.base.InMemorySource;
import com.aerotrace.search.elastic.IndexAdmin;
import com.aerotrace.search.elastic.IndexFamily;
import com.aerotrace.search.elastic.SupportIndex;
import com.aerotrace.search.reindex.ReindexJob.Scope;
import com.aerotrace.search.reindex.ReindexJob.Status;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * What keeps a rebuild safe when things go wrong: the lock is only ever renewed or released by the
 * job that holds it, a job that lost it stops, a crashed job is cleaned up by the next one, both
 * families swap in one atomic call, a source that pages forever fails the job, and an
 * Idempotency-Key cannot be reused for another request.
 */
@SpringBootTest(properties = "aerotrace.search.reindex.heartbeat=200ms")
@AutoConfigureMockMvc
@Import(InMemorySource.Config.class)
class ReindexSafetyTest extends AbstractSearchTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Autowired MockMvc mockMvc;
  @Autowired InMemorySource source;
  @Autowired ReindexJobStore store;
  @Autowired ElasticsearchClient client;
  @MockitoSpyBean IndexAdmin admin;

  @BeforeEach
  void freshSource() {
    source.reset();
  }

  private void seed(int count) {
    String run = UUID.randomUUID().toString().substring(0, 6);
    for (int i = 0; i < count; i++) {
      String caseId = "safety-" + run + "-" + String.format("%04d", i);
      source.cases.put(
          caseId,
          new CaseChangedPayload(
              caseId,
              newBagTag(),
              newId("leg"),
              RecoveryState.OPEN,
              1,
              null,
              Instant.parse("2026-04-01T00:00:00Z"),
              null,
              CaseTrigger.RISK,
              null));
    }
  }

  private static RequestPostProcessor admin(String subject) {
    return SecurityMockMvcRequestPostProcessors.jwt()
        .jwt(token -> token.subject(subject).claim(JwtClaims.ROLES, List.of(Role.ADMIN.name())))
        .authorities(new SimpleGrantedAuthority(Role.ADMIN.authority()));
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
            .andReturn()
            .getResponse()
            .getContentAsString();
    return JSON.readTree(body).get("data").get("jobId").asString();
  }

  private JsonNode job(String jobId) throws Exception {
    return JSON.readTree(
            mockMvc
                .perform(get("/api/v1/admin/search/reindex/{id}", jobId).with(as(Role.ADMIN)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString())
        .get("data");
  }

  private JsonNode finished(String jobId) throws Exception {
    await()
        .atMost(Duration.ofSeconds(120))
        .until(() -> !"RUNNING".equals(job(jobId).get("status").asString()));
    return job(jobId);
  }

  @Test
  @DisplayName(
      "an expired lease is taken over; the old holder can then neither renew nor release the"
          + " new holder's lock")
  void lockTakeover() throws Exception {
    String dead = UUID.randomUUID().toString();
    String next = UUID.randomUUID().toString();
    assertThat(store.lock(dead, Duration.ZERO).acquired()).isTrue();

    ReindexJobStore.Lock taken = store.lock(next, Duration.ofMinutes(1));

    assertThat(taken).isEqualTo(new ReindexJobStore.Lock(true, dead));
    assertThat(store.renew(dead, Duration.ofMinutes(1))).isFalse();
    store.unlock(dead);
    assertThat(store.holds(next)).isTrue();
    assertThat(store.renew(next, Duration.ofMinutes(1))).isTrue();
    store.unlock(next);
    assertThat(store.holds(next)).isFalse();
  }

  @Test
  @DisplayName(
      "a job whose lock was taken over stops at its next heartbeat: FAILED, no swap, and the"
          + " other job's lock is left alone")
  void staleHeartbeatAbortsTheJob() throws Exception {
    seed(5);
    String before = admin.readIndex(IndexFamily.CASES);
    CountDownLatch gate = new CountDownLatch(1);
    source.gate = gate;
    String jobId = start(UUID.randomUUID().toString(), "CASES");
    String other = UUID.randomUUID().toString();
    try {
      // Another instance took the lock over (as if this job's lease had run out).
      client.index(
          i ->
              i.index(SupportIndex.JOBS.index())
                  .id("lock")
                  .document(
                      Map.of(
                          "kind",
                          "LOCK",
                          "jobId",
                          other,
                          "leaseUntil",
                          Instant.now().plusSeconds(3600).toEpochMilli())));
      JsonNode job = finished(jobId);

      assertThat(job.get("status").asString()).isEqualTo("FAILED");
      assertThat(job.get("error").asString()).isEqualTo(ReindexService.LOCK_LOST);
      assertThat(admin.readIndex(IndexFamily.CASES)).isEqualTo(before);
      assertThat(admin.liveIndexes(IndexFamily.CASES)).containsExactly(before);
      assertThat(store.holds(other)).isTrue();
    } finally {
      gate.countDown();
      store.unlock(other);
    }
  }

  @Test
  @DisplayName(
      "a rebuild whose instance died is marked FAILED by the job that takes its lock over, and"
          + " its half-built index leaves the live alias and is deleted")
  void crashedRebuildIsCleanedUp() throws Exception {
    String dead = UUID.randomUUID().toString();
    store.save(
        new ReindexJob(
            dead,
            Status.RUNNING,
            Scope.CASES,
            "user-admin",
            Instant.now().minusSeconds(600),
            null,
            null,
            Map.of()));
    String halfBuilt = admin.createNext(IndexFamily.CASES);
    admin.addToLive(IndexFamily.CASES, halfBuilt);
    assertThat(store.lock(dead, Duration.ZERO).acquired()).isTrue();
    seed(3);

    JsonNode job = finished(start(UUID.randomUUID().toString(), "CASES"));

    assertThat(job.get("status").asString()).isEqualTo("SUCCEEDED");
    JsonNode abandoned = job(dead);
    assertThat(abandoned.get("status").asString()).isEqualTo("FAILED");
    assertThat(abandoned.get("error").asString()).isEqualTo(ReindexService.ABANDONED);
    assertThat(admin.liveIndexes(IndexFamily.CASES))
        .containsExactly(admin.readIndex(IndexFamily.CASES))
        .doesNotContain(halfBuilt);
    assertThat(client.indices().exists(e -> e.index(halfBuilt)).value()).isFalse();
  }

  @Test
  @DisplayName(
      "scope ALL swaps both families in one atomic call: when Elasticsearch refuses it,"
          + " neither alias moves, the job fails and both new indexes are discarded")
  void swapIsAtomic() throws Exception {
    seed(3);
    String bag = newBagTag();
    source.bags.put(
        bag,
        new BagExport(
            bag,
            List.of(new LegSnapshot(newId("leg"), "TK1", "TK", "IST", "LHR", Instant.now())),
            null));
    String casesBefore = admin.readIndex(IndexFamily.CASES);
    String journeysBefore = admin.readIndex(IndexFamily.JOURNEYS);
    doAnswer(
            call -> {
              Map<IndexFamily, String> next = call.getArgument(0);
              // The journeys index vanishes just before the swap: the add action must fail.
              client.indices().delete(d -> d.index(next.get(IndexFamily.JOURNEYS)));
              return call.callRealMethod();
            })
        .when(admin)
        .swapRead(anyMap());

    JsonNode job = finished(start(UUID.randomUUID().toString(), "ALL"));

    assertThat(job.get("status").asString()).isEqualTo("FAILED");
    assertThat(admin.readIndex(IndexFamily.CASES)).isEqualTo(casesBefore);
    assertThat(admin.readIndex(IndexFamily.JOURNEYS)).isEqualTo(journeysBefore);
    assertThat(admin.liveIndexes(IndexFamily.CASES)).containsExactly(casesBefore);
    assertThat(admin.liveIndexes(IndexFamily.JOURNEYS)).containsExactly(journeysBefore);
    String newCases = job.get("results").get("cases").get("index").asString();
    assertThat(client.indices().exists(e -> e.index(newCases)).value()).isFalse();
  }

  @Test
  @DisplayName("a source whose paging does not advance fails the job instead of looping")
  void pagingMustAdvance() throws Exception {
    seed(150); // two pages of 100
    source.stuckPaging = true;
    String before = admin.readIndex(IndexFamily.CASES);

    JsonNode job = finished(start(UUID.randomUUID().toString(), "CASES"));

    assertThat(job.get("status").asString()).isEqualTo("FAILED");
    assertThat(job.get("error").asString()).isEqualTo("cases: the export did not advance");
    assertThat(admin.readIndex(IndexFamily.CASES)).isEqualTo(before);
  }

  @Test
  @DisplayName(
      "an Idempotency-Key reused for another scope or by another caller is 422"
          + " IDEMPOTENCY_KEY_REUSED; an expired key may be used again")
  void idempotencyKeyBelongsToItsRequest() throws Exception {
    seed(2);
    String key = UUID.randomUUID().toString();
    String jobId = start(key, "CASES");
    finished(jobId);

    mockMvc
        .perform(
            post("/api/v1/admin/search/reindex")
                .param("scope", "JOURNEYS")
                .header("Idempotency-Key", key)
                .with(as(Role.ADMIN)))
        .andExpect(status().is(422))
        .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
    mockMvc
        .perform(
            post("/api/v1/admin/search/reindex")
                .param("scope", "CASES")
                .header("Idempotency-Key", key)
                .with(admin("another-admin")))
        .andExpect(status().is(422))
        .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
    assertThat(start(key, "CASES")).isEqualTo(jobId);

    // A key past its time to live names nothing any more.
    String old = UUID.randomUUID().toString();
    client.index(
        i ->
            i.index(SupportIndex.JOBS.index())
                .id("key:" + ReindexJobStore.hash(old))
                .document(
                    Map.of(
                        "kind",
                        "KEY",
                        "jobId",
                        jobId,
                        "scope",
                        "JOURNEYS",
                        "requestedBy",
                        "someone-else",
                        "startedAt",
                        Instant.now().minus(Duration.ofDays(2)).toEpochMilli())));
    String fresh = start(old, "CASES");
    assertThat(fresh).isNotEqualTo(jobId);
    finished(fresh);
  }
}
