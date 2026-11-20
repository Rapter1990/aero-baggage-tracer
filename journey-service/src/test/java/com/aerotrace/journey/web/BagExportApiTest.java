package com.aerotrace.journey.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aerotrace.common.export.ExportApi;
import com.aerotrace.common.security.JwtClaims;
import com.aerotrace.common.security.PemKeyUtils;
import com.aerotrace.common.security.Role;
import com.aerotrace.common.security.ServiceToken;
import com.aerotrace.journey.base.AbstractApiTest;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.security.PrivateKey;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * The internal bag export (#28) through the real application and a real PostgreSQL: keyset paging
 * without gaps or repeats, the service token, read-only, and the shape the search-service reads.
 * The database is shared with other tests, so the assertions are about this test's own bags.
 */
class BagExportApiTest extends AbstractApiTest {

  /** The service token of the {@code test} profile (application-test.yml). */
  static final String TOKEN = "test-export-service-token";

  private MockHttpServletRequestBuilder export(String after, Integer size) {
    var request = get(ExportApi.BAGS).header(ServiceToken.HEADER, TOKEN);
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

  private JsonNode exported(String bagTag) throws Exception {
    return exportAll(ExportApi.MAX_SIZE, () -> {}).stream()
        .filter(b -> bagTag.equals(b.get("bagTag").asString()))
        .findFirst()
        .orElseThrow();
  }

  private String registered() {
    try {
      return registeredBag(newPassengerRef());
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private Object legColumn(String bagTag, int legNo, String column) {
    return new TransactionTemplate(transactionManager)
        .execute(
            status ->
                entityManager
                    .createNativeQuery(
                        "SELECT "
                            + column
                            + " FROM journey.itinerary_leg l"
                            + " JOIN journey.itinerary i ON i.id = l.itinerary_id"
                            + " JOIN journey.baggage b ON b.id = i.baggage_id"
                            + " WHERE b.bag_tag = ?1 AND l.leg_no = ?2")
                    .setParameter(1, bagTag)
                    .setParameter(2, legNo)
                    .getSingleResult());
  }

  /** Row counts and an order-independent checksum of every journey table. */
  private Map<String, String> sourceFingerprint() {
    Map<String, String> fingerprint = new LinkedHashMap<>();
    for (String table :
        List.of(
            "baggage",
            "itinerary",
            "itinerary_leg",
            "scan_audit",
            "outbox_event",
            "processed_event")) {
      Object value =
          new TransactionTemplate(transactionManager)
              .execute(
                  status ->
                      entityManager
                          .createNativeQuery(
                              "SELECT count(*) || ':' || coalesce(sum(hashtext(t::text)::bigint), 0)"
                                  + " FROM journey."
                                  + table
                                  + " t")
                          .getSingleResult());
      fingerprint.put(table, String.valueOf(value));
    }
    return fingerprint;
  }

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

  @Test
  @DisplayName(
      "the export pages through more bags than one page with no gap and no repeat while bags are"
          + " registered, in ascending bagTag order")
  void pagesWithoutGapsOrRepeats() throws Exception {
    Set<String> seeded = new HashSet<>();
    for (int i = 0; i < 130; i++) {
      seeded.add(registered());
    }
    Set<String> registeredDuring = new HashSet<>();

    List<JsonNode> exported =
        exportAll(
            50,
            () -> {
              for (int i = 0; i < 3; i++) {
                registeredDuring.add(registered());
              }
            });

    List<String> tags = exported.stream().map(b -> b.get("bagTag").asString()).toList();
    assertThat(tags).doesNotHaveDuplicates().containsAll(seeded).isSorted();
    assertThat(tags.size()).isGreaterThan(100);
    assertThat(tags.stream().filter(registeredDuring::contains).count())
        .isLessThanOrEqualTo(registeredDuring.size());
  }

  @Test
  @DisplayName(
      "an exported bag has every leg (legId, flightNumber, airline, origin, destination,"
          + " departureAt) and its latest accepted scan, and no passenger data")
  void exportsTheBagWithLegsAndLatestScan() throws Exception {
    String bagTag = registered();
    OffsetDateTime older = OffsetDateTime.now().minusMinutes(4);
    OffsetDateTime newer = OffsetDateTime.now().minusMinutes(2);
    scan(barcodeScan(bagTag, FRANKFURT, "TRANSFER", 7, newer), newKey(), as(Role.AGENT))
        .andExpect(status().isAccepted());
    // Older by event time: audited, but not the latest accepted state.
    scan(barcodeScan(bagTag, ISTANBUL, "CHECK_IN", 9, older), newKey(), as(Role.AGENT))
        .andExpect(status().isAccepted());

    JsonNode bag = exported(bagTag);

    assertThat(bag.propertyNames()).containsExactlyInAnyOrder("bagTag", "legs", "latestScan");
    JsonNode legs = bag.get("legs");
    assertThat(legs).hasSize(2);
    assertThat(legs.get(0).propertyNames())
        .containsExactlyInAnyOrder(
            "legId", "flightNumber", "airline", "origin", "destination", "departureAt");
    assertThat(legs.get(0).get("legId").asString())
        .isEqualTo(legColumn(bagTag, 1, "l.id").toString());
    assertThat(legs.get(0).get("flightNumber").asString()).isEqualTo("TK1234");
    assertThat(legs.get(0).get("airline").asString()).isEqualTo("TK");
    assertThat(legs.get(0).get("origin").asString()).isEqualTo(ISTANBUL);
    assertThat(legs.get(0).get("destination").asString()).isEqualTo(FRANKFURT);
    assertThat(legs.get(1).get("legId").asString())
        .isEqualTo(legColumn(bagTag, 2, "l.id").toString());
    assertThat(legs.get(1).get("flightNumber").asString()).isEqualTo("LH902");
    assertThat(legs.get(1).get("airline").asString()).isEqualTo("LH");
    assertThat(legs.get(1).get("origin").asString()).isEqualTo(FRANKFURT);
    assertThat(legs.get(1).get("destination").asString()).isEqualTo(LONDON);
    assertThat(Instant.parse(legs.get(1).get("departureAt").asString()))
        .isAfter(Instant.parse(legs.get(0).get("departureAt").asString()));

    JsonNode latest = bag.get("latestScan");
    assertThat(latest.propertyNames())
        .containsExactlyInAnyOrder(
            "airport", "checkpoint", "legId", "occurredAt", "deviceSequence");
    assertThat(latest.get("airport").asString()).isEqualTo(FRANKFURT);
    assertThat(latest.get("checkpoint").asString()).isEqualTo("TRANSFER");
    assertThat(latest.get("deviceSequence").asLong()).isEqualTo(7);
    assertThat(Instant.parse(latest.get("occurredAt").asString()))
        .isCloseTo(newer.toInstant(), within(1, ChronoUnit.MILLIS));
    // The same leg rule as the scan event: a TRANSFER at FRA belongs to the FRA departure.
    assertThat(latest.get("legId").asString()).isEqualTo(legColumn(bagTag, 2, "l.id").toString());

    // A bag that has not been scanned has no latest scan.
    assertThat(exported(registered()).get("latestScan").isNull()).isTrue();
  }

  @Test
  @DisplayName("the export writes nothing: every journey table is unchanged by a full export")
  void writesNothing() throws Exception {
    for (int i = 0; i < 5; i++) {
      registered();
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
        .perform(get(ExportApi.BAGS))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    mockMvc
        .perform(get(ExportApi.BAGS).header(ServiceToken.HEADER, TOKEN + "-wrong"))
        .andExpect(status().isUnauthorized());
    mockMvc
        .perform(get(ExportApi.BAGS).header("Authorization", "Bearer " + adminToken()))
        .andExpect(status().isUnauthorized());
    mockMvc
        .perform(post(ExportApi.BAGS).header(ServiceToken.HEADER, TOKEN))
        .andExpect(status().isForbidden());
    mockMvc
        .perform(get(ExportApi.CASES).header(ServiceToken.HEADER, TOKEN))
        .andExpect(status().isForbidden());
    // The token opens nothing outside /internal/**.
    mockMvc
        .perform(get("/api/v1/bags/{tag}/journey", newBagTag()).header(ServiceToken.HEADER, TOKEN))
        .andExpect(status().isUnauthorized());
  }

  @Test
  @DisplayName("the page size is bounded (1 to 500) and after must be a bag tag; otherwise 400")
  void validatesTheRequest() throws Exception {
    mockMvc.perform(export(null, 0)).andExpect(status().isBadRequest());
    mockMvc
        .perform(export(null, ExportApi.MAX_SIZE + 1))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    mockMvc.perform(export("12345", 10)).andExpect(status().isBadRequest());
    mockMvc.perform(export("abcdefghij", 10)).andExpect(status().isBadRequest());
    mockMvc.perform(export(null, ExportApi.MAX_SIZE)).andExpect(status().isOk());
    mockMvc
        .perform(export(null, null))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.items").isArray());
  }
}
