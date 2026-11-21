package com.aerotrace.recovery.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aerotrace.common.export.ExportApi;
import com.aerotrace.common.model.RecoveryState;
import com.aerotrace.common.security.JwtClaims;
import com.aerotrace.common.security.PemKeyUtils;
import com.aerotrace.common.security.Role;
import com.aerotrace.common.security.ServiceToken;
import com.aerotrace.recovery.base.AbstractRecoveryTest;
import com.aerotrace.recovery.consumer.RiskEventHandler;
import com.aerotrace.recovery.model.dto.request.AssignRequest;
import com.aerotrace.recovery.model.dto.request.CreateCaseRequest;
import com.aerotrace.recovery.model.dto.request.TransitionRequest;
import com.aerotrace.recovery.model.dto.response.CaseResponse;
import com.aerotrace.recovery.service.RecoveryCaseService;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.security.PrivateKey;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The internal case export (#29) through the real application and a real PostgreSQL: keyset paging
 * without gaps or repeats, the service token, read-only, and the revision 2 fields. The database is
 * shared with other tests, so the assertions are about this test's own cases.
 */
@SpringBootTest
@AutoConfigureMockMvc
class CaseExportApiTest extends AbstractRecoveryTest {

  /** The service token of the {@code test} profile (application-test.yml). */
  static final String TOKEN = "test-export-service-token";

  @Autowired MockMvc mockMvc;
  @Autowired ObjectMapper json;
  @Autowired RecoveryCaseService service;
  @Autowired RiskEventHandler riskEvents;
  @Autowired JdbcTemplate jdbc;
  @MockitoBean KafkaTemplate<String, String> kafka;

  /** A real admin token signed with the identity-service's development key. */
  private static String adminToken() throws Exception {
    PrivateKey key = PemKeyUtils.readPrivateKey(null, "classpath:test-keys/private.pem");
    JWTClaimsSet claims =
        new JWTClaimsSet.Builder()
            .subject("admin-1")
            .issuer(JwtClaims.ISSUER)
            .audience("aerotrace-api")
            .claim(JwtClaims.ROLES, List.of(Role.ADMIN.name()))
            .issueTime(Date.from(Instant.now().minusSeconds(30)))
            .expirationTime(Date.from(Instant.now().plusSeconds(600)))
            .build();
    SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims);
    jwt.sign(new RSASSASigner(key));
    return jwt.serialize();
  }

  private static String newBagTag() {
    return String.format("%010d", ThreadLocalRandom.current().nextLong(10_000_000_000L));
  }

  private CaseResponse open() {
    return service.create(new CreateCaseRequest(newBagTag(), "LEG-2", "seeded"), null);
  }

  private MockHttpServletRequestBuilder export(String after, Integer size) {
    var request = get(ExportApi.CASES).header(ServiceToken.HEADER, TOKEN);
    if (after != null) {
      request.param("after", after);
    }
    if (size != null) {
      request.param("size", String.valueOf(size));
    }
    return request;
  }

  private JsonNode page(String after, int size) throws Exception {
    String body =
        mockMvc
            .perform(export(after, size))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return json.readTree(body).get("data");
  }

  /** Every exported case, in the order the pages gave them. */
  private List<JsonNode> exportAll(int size, Runnable betweenPages) throws Exception {
    List<JsonNode> all = new ArrayList<>();
    String after = null;
    do {
      JsonNode page = page(after, size);
      page.get("items").forEach(all::add);
      after = page.get("nextAfter").isNull() ? null : page.get("nextAfter").asString();
      betweenPages.run();
    } while (after != null);
    return all;
  }

  private Map<String, JsonNode> byId(List<JsonNode> items) {
    Map<String, JsonNode> map = new LinkedHashMap<>();
    items.forEach(item -> map.put(item.get("caseId").asString(), item));
    return map;
  }

  /**
   * Row counts and an order-independent checksum of every recovery table the export could touch.
   */
  private Map<String, String> sourceFingerprint() {
    Map<String, String> fingerprint = new LinkedHashMap<>();
    for (String table :
        List.of("recovery_case", "state_transition", "outbox_event", "processed_event")) {
      fingerprint.put(
          table,
          jdbc.queryForObject(
              "select count(*) || ':' || coalesce(sum(hashtext(t::text)::bigint), 0)"
                  + " from recovery."
                  + table
                  + " t",
              String.class));
    }
    return fingerprint;
  }

  @Test
  @DisplayName(
      "the export pages through more cases than one page with no gap and no repeat while cases"
          + " are inserted, in ascending caseId order")
  void pagesWithoutGapsOrRepeats() throws Exception {
    Set<String> seeded = new HashSet<>();
    for (int i = 0; i < 130; i++) {
      seeded.add(open().caseId().toString());
    }
    Set<String> insertedDuring = new HashSet<>();

    List<JsonNode> exported =
        exportAll(
            50,
            () -> {
              for (int i = 0; i < 3; i++) {
                insertedDuring.add(open().caseId().toString());
              }
            });

    List<String> ids = exported.stream().map(c -> c.get("caseId").asString()).toList();
    assertThat(ids).doesNotHaveDuplicates().containsAll(seeded).isSorted();
    assertThat(ids.size()).isGreaterThan(100);
    // A case inserted meanwhile is exported at most once (once if its id is after the cursor).
    assertThat(ids.stream().filter(insertedDuring::contains).count())
        .isLessThanOrEqualTo(insertedDuring.size());
  }

  @Test
  @DisplayName(
      "an exported case carries version, openedAt, resolvedAt, trigger and assignedTo, as its"
          + " revision 2 event does")
  void exportsTheFullCase() throws Exception {
    CaseResponse manual = open();
    UUID id = manual.caseId();
    long v = service.assign(id, new AssignRequest("agent-7", 1L)).version();
    v =
        service
            .transition(id, new TransitionRequest(RecoveryState.ASSESSING, v, "check"))
            .version();
    v = service.transition(id, new TransitionRequest(RecoveryState.RESERVED, v, "hold")).version();
    v = service.transition(id, new TransitionRequest(RecoveryState.IN_TRANSIT, v, "go")).version();
    CaseResponse resolved =
        service.transition(id, new TransitionRequest(RecoveryState.RESOLVED, v, "delivered"));
    String riskBag = newBagTag();
    riskEvents.handle(CaseEventContractTest.highRisk(riskBag, "LEG-3", "corr-export"));

    Map<String, JsonNode> exported = byId(exportAll(ExportApi.MAX_SIZE, () -> {}));

    JsonNode c = exported.get(id.toString());
    assertThat(c.get("bagTag").asString()).isEqualTo(manual.bagTag());
    assertThat(c.get("legId").asString()).isEqualTo("LEG-2");
    assertThat(c.get("state").asString()).isEqualTo("RESOLVED");
    assertThat(c.get("version").asLong()).isEqualTo(resolved.version()).isEqualTo(6);
    assertThat(c.get("trigger").asString()).isEqualTo("MANUAL");
    assertThat(c.get("assignedTo").asString()).isEqualTo("agent-7");
    Instant openedAt = Instant.parse(c.get("openedAt").asString());
    Instant resolvedAt = Instant.parse(c.get("resolvedAt").asString());
    // The database keeps microseconds; the answers of the service calls may carry more.
    assertThat(openedAt).isCloseTo(manual.createdAt(), within(1, ChronoUnit.MILLIS));
    assertThat(resolvedAt)
        .isCloseTo(resolved.updatedAt(), within(1, ChronoUnit.MILLIS))
        .isAfterOrEqualTo(openedAt);
    // No passenger data and nothing the event does not carry.
    assertThat(c.propertyNames())
        .containsExactlyInAnyOrder(
            "caseId",
            "bagTag",
            "legId",
            "state",
            "version",
            "reason",
            "openedAt",
            "resolvedAt",
            "trigger",
            "assignedTo");

    JsonNode fromRisk =
        exported.values().stream()
            .filter(e -> riskBag.equals(e.get("bagTag").asString()))
            .findFirst()
            .orElseThrow();
    assertThat(fromRisk.get("trigger").asString()).isEqualTo("RISK");
    assertThat(fromRisk.get("resolvedAt").isNull()).isTrue();
    assertThat(fromRisk.get("assignedTo").isNull()).isTrue();
  }

  @Test
  @DisplayName("the export writes nothing: every recovery table is unchanged by a full export")
  void writesNothing() throws Exception {
    for (int i = 0; i < 5; i++) {
      open();
    }
    Map<String, String> before = sourceFingerprint();

    exportAll(7, () -> {});
    exportAll(ExportApi.MAX_SIZE, () -> {});

    assertThat(sourceFingerprint()).isEqualTo(before);
  }

  @Test
  @DisplayName(
      "the export requires the service token: none, a wrong one or a user token is 401, any other"
          + " method or internal path is 403")
  void requiresTheServiceToken() throws Exception {
    mockMvc
        .perform(get(ExportApi.CASES))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    mockMvc
        .perform(get(ExportApi.CASES).header(ServiceToken.HEADER, TOKEN + "-wrong"))
        .andExpect(status().isUnauthorized());
    // Even a valid admin token is no way in: the internal chain accepts the service token only.
    mockMvc
        .perform(get(ExportApi.CASES).header("Authorization", "Bearer " + adminToken()))
        .andExpect(status().isUnauthorized());
    mockMvc
        .perform(get("/api/v1/recovery-cases").header("Authorization", "Bearer " + adminToken()))
        .andExpect(status().isOk());
    mockMvc
        .perform(post(ExportApi.CASES).header(ServiceToken.HEADER, TOKEN))
        .andExpect(status().isForbidden());
    mockMvc
        .perform(get("/internal/v1/export/other").header(ServiceToken.HEADER, TOKEN))
        .andExpect(status().isForbidden());
    // The token opens nothing outside /internal/**.
    mockMvc
        .perform(get("/api/v1/recovery-cases").header(ServiceToken.HEADER, TOKEN))
        .andExpect(status().isUnauthorized());
  }

  @Test
  @DisplayName("the page size is bounded (1 to 500) and after must be a case id; otherwise 400")
  void validatesTheRequest() throws Exception {
    mockMvc.perform(export(null, 0)).andExpect(status().isBadRequest());
    mockMvc
        .perform(export(null, ExportApi.MAX_SIZE + 1))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    mockMvc.perform(export("not-a-case-id", 10)).andExpect(status().isBadRequest());
    mockMvc.perform(export(null, ExportApi.MAX_SIZE)).andExpect(status().isOk());
    // Without a size the default page is used.
    mockMvc
        .perform(export(null, null))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.items").isArray());
  }
}
