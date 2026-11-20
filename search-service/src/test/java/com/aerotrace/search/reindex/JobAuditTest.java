package com.aerotrace.search.reindex;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.aerotrace.common.security.Role;
import com.aerotrace.search.base.AbstractSearchTest;
import com.aerotrace.search.base.InMemorySource;
import com.aerotrace.search.elastic.SupportIndex;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

/**
 * Spring Data auditing of the job documents (ADR 0003): {@code @CreatedDate},
 * {@code @LastModifiedDate}, {@code @CreatedBy} and {@code @LastModifiedBy}, in the real index.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(InMemorySource.Config.class)
class JobAuditTest extends AbstractSearchTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Autowired MockMvc mockMvc;
  @Autowired ElasticsearchClient client;
  @Autowired ReindexJobStore store;

  @SuppressWarnings({"rawtypes", "unchecked"})
  private Map<String, Object> jobDocument(String jobId) throws Exception {
    return client
        .get(g -> g.index(SupportIndex.JOBS.index()).id("job:" + jobId), Map.class)
        .source();
  }

  @Test
  @DisplayName(
      "a job records who created it and when, keeps that after the worker finishes it, and"
          + " records who and when last changed it (the worker is 'system')")
  void jobDocumentIsAudited() throws Exception {
    String body =
        mockMvc
            .perform(
                post("/api/v1/admin/search/reindex")
                    .param("scope", "CASES")
                    .header("Idempotency-Key", java.util.UUID.randomUUID().toString())
                    .with(as(Role.ADMIN)))
            .andExpect(status().isAccepted())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String jobId = JSON.readTree(body).get("data").get("jobId").asString();

    await()
        .atMost(Duration.ofSeconds(120))
        .until(
            () -> {
              String view =
                  mockMvc
                      .perform(get("/api/v1/admin/search/reindex/{id}", jobId).with(as(Role.ADMIN)))
                      .andReturn()
                      .getResponse()
                      .getContentAsString();
              return !"RUNNING".equals(JSON.readTree(view).get("data").get("status").asString());
            });

    // The status is saved just before the lock is released: leave nothing held for the next test.
    await().atMost(Duration.ofSeconds(30)).until(() -> !store.holds(jobId));

    Map<String, Object> doc = jobDocument(jobId);
    long createdAt = Long.parseLong(String.valueOf(doc.get("createdAt")));
    long lastModifiedAt = Long.parseLong(String.valueOf(doc.get("lastModifiedAt")));
    // Created on the request thread by the admin (the token's subject); finished by the worker.
    assertThat(doc.get("createdBy")).isEqualTo("user-admin");
    assertThat(doc.get("lastModifiedBy")).isEqualTo("system");
    assertThat(lastModifiedAt).isGreaterThanOrEqualTo(createdAt);
    assertThat(doc.get("status")).isNotEqualTo("RUNNING");
    // The audit fields are not part of the API view of the job.
    String view =
        mockMvc
            .perform(get("/api/v1/admin/search/reindex/{id}", jobId).with(as(Role.ADMIN)))
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(view).doesNotContain("createdBy").doesNotContain("lastModifiedBy");
  }
}
