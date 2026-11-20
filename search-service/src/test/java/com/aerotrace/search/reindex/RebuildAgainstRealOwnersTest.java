package com.aerotrace.search.reindex;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.aerotrace.common.event.CaseChangedPayload;
import com.aerotrace.common.export.ExportApi;
import com.aerotrace.common.model.RecoveryState;
import com.aerotrace.common.security.Role;
import com.aerotrace.common.security.ServiceToken;
import com.aerotrace.search.base.AbstractSearchTest;
import com.aerotrace.search.base.Events;
import com.aerotrace.search.base.RealOwners;
import com.aerotrace.search.elastic.IndexAdmin;
import com.aerotrace.search.elastic.IndexFamily;
import com.aerotrace.search.indexer.EventIndexer;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * AT-18 "Rebuild Elasticsearch from source data with no duplicate documents", end to end.
 *
 * <p>Everything on the path is real: the search-service (this Spring context, with its real {@link
 * HttpSourceExportClient}, rebuild job, indexer and Kafka listeners) rebuilds a real Elasticsearch
 * (the pinned Testcontainers image) from the real {@code GET /internal/v1/export/...} endpoints of
 * the packaged recovery-service (#29) and journey-service (#28), running as their own processes on
 * a real PostgreSQL ({@link RealOwners}) and publishing their events through their outboxes to the
 * real Kafka this context consumes. The source data is created through the owners' public APIs
 * (more cases and bags than one export page, cases at different versions), so the versions are the
 * ones their state machine produced.
 *
 * <p>Not on the path: the gateway (it has no route to {@code /internal/**} by design), Compose and
 * the identity-service (the owners verify tokens signed with a key made for the run).
 */
@SpringBootTest
@AutoConfigureMockMvc
class RebuildAgainstRealOwnersTest extends AbstractSearchTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  /** More than one export page in the test profile (page size 100). */
  private static final int CASES = 150;

  private static final int BAGS = 120;

  private static RealOwners owners;

  @Autowired MockMvc mockMvc;
  @Autowired ElasticsearchClient client;
  @Autowired IndexAdmin admin;
  @Autowired EventIndexer indexer;

  @BeforeAll
  static void startOwners() {
    boolean built = RealOwners.available();
    if (System.getenv("CI") != null) {
      if (!built) {
        throw new AssertionError("build journey-service and recovery-service before this test");
      }
    } else {
      assumeTrue(built, "the journey-service and recovery-service jars are not built");
    }
    owners();
  }

  private static synchronized RealOwners owners() {
    if (owners == null) {
      try {
        owners = RealOwners.start(kafkaBootstrapServers());
      } catch (Exception e) {
        throw new IllegalStateException("the owning services did not start", e);
      }
    }
    return owners;
  }

  @AfterAll
  static synchronized void stopOwners() {
    if (owners != null) {
      owners.close();
      owners = null;
    }
  }

  @DynamicPropertySource
  static void realOwners(DynamicPropertyRegistry registry) {
    registry.add("aerotrace.search.export.recovery-url", () -> owners().recoveryUrl());
    registry.add("aerotrace.search.export.journey-url", () -> owners().journeyUrl());
    registry.add("aerotrace.search.export.service-token", () -> RealOwners.SERVICE_TOKEN);
  }

  // ------------------------------------------------------------------ the source, through its APIs

  private record SeededCase(String caseId, long version) {}

  private static JsonNode move(String caseId, RecoveryState target, long version) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("targetState", target.name());
    body.put("expectedVersion", version);
    body.put("reason", "e2e " + target.name().toLowerCase(java.util.Locale.ROOT));
    return owners()
        .call(
            "recovery",
            "PATCH",
            "/api/v1/recovery-cases/" + caseId + "/transitions",
            body,
            "AGENT",
            Map.of(),
            200);
  }

  /** Opens cases and moves them on, so the source holds versions 1, 2, 3 and 5. */
  private static List<SeededCase> seedCases() {
    long base = ThreadLocalRandom.current().nextLong(1_000_000_000L, 9_000_000_000L);
    List<SeededCase> seeded = new ArrayList<>();
    for (int i = 0; i < CASES; i++) {
      Map<String, Object> open = new LinkedHashMap<>();
      open.put("bagTag", String.format("%010d", base + i));
      open.put("legId", "LEG-" + (i % 4 + 1));
      open.put("reason", "e2e seeded");
      String id =
          owners()
              .call("recovery", "POST", "/api/v1/recovery-cases", open, "AGENT", Map.of(), 201)
              .get("caseId")
              .asString();
      long version = 1;
      switch (i % 5) {
        case 1 ->
            version =
                owners()
                    .call(
                        "recovery",
                        "PUT",
                        "/api/v1/recovery-cases/" + id + "/assignment",
                        Map.of("assignee", "agent-" + i, "expectedVersion", 1),
                        "AGENT",
                        Map.of(),
                        200)
                    .get("version")
                    .asLong();
        case 2 -> {
          version = move(id, RecoveryState.ASSESSING, version).get("version").asLong();
          version = move(id, RecoveryState.CANCELLED, version).get("version").asLong();
        }
        case 3 -> {
          version = move(id, RecoveryState.ASSESSING, version).get("version").asLong();
          version = move(id, RecoveryState.RESERVED, version).get("version").asLong();
          version = move(id, RecoveryState.IN_TRANSIT, version).get("version").asLong();
          version = move(id, RecoveryState.RESOLVED, version).get("version").asLong();
        }
        case 4 ->
            version =
                move(id, RecoveryState.CLOSED_AS_UNRECOVERABLE, version).get("version").asLong();
        default -> {}
      }
      seeded.add(new SeededCase(id, version));
    }
    return seeded;
  }

  /** Registers bags with two legs each (four airlines) and scans every third one. */
  private static List<String> seedBags() {
    long base = ThreadLocalRandom.current().nextLong(1_000_000_000L, 9_000_000_000L);
    String[] airlines = {"TK", "LH", "4U", "BA"};
    Instant departure = Instant.now().plus(Duration.ofDays(2));
    List<String> tags = new ArrayList<>();
    for (int i = 0; i < BAGS; i++) {
      String tag = String.format("%010d", base + i);
      String airline = airlines[i % airlines.length];
      Map<String, Object> body = new LinkedHashMap<>();
      body.put("bagTag", tag);
      body.put("passengerRef", String.format("P-%020d", base + i));
      body.put(
          "legs",
          List.of(
              leg(airline + (100 + i), "IST", "FRA", departure, departure.plusSeconds(3 * 3600)),
              leg(
                  "LH" + (900 + i),
                  "FRA",
                  "LHR",
                  departure.plusSeconds(5 * 3600),
                  departure.plusSeconds(7 * 3600))));
      owners().call("journey", "POST", "/api/v1/bags", body, "AGENT", Map.of(), 201);
      if (i % 3 == 0) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("bagTag", tag);
        payload.put("airport", "IST");
        payload.put("checkpoint", "CHECK_IN");
        payload.put("sequence", i + 1);
        payload.put("scannedAt", OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(5).toString());
        owners()
            .call(
                "journey",
                "POST",
                "/api/v1/scans",
                Map.of("protocol", "BARCODE", "payload", payload),
                "AGENT",
                Map.of("Idempotency-Key", "e2e-" + UUID.randomUUID()),
                202);
      }
      tags.add(tag);
    }
    return tags;
  }

  private static Map<String, Object> leg(
      String flight, String from, String to, Instant departure, Instant arrival) {
    Map<String, Object> leg = new LinkedHashMap<>();
    leg.put("flightNumber", flight);
    leg.put("origin", from);
    leg.put("destination", to);
    leg.put("departureAt", departure.toString());
    leg.put("arrivalAt", arrival.toString());
    return leg;
  }

  // ------------------------------------------------------------------ the source, as the oracle

  /** caseId to version, read from the recovery-service's own table (the test's oracle only). */
  private static Map<String, Long> sourceCases() throws Exception {
    Map<String, Long> cases = new TreeMap<>();
    for (Object[] row :
        owners()
            .query(
                "SELECT id::text, version FROM recovery.recovery_case",
                r -> new Object[] {r.getString(1), r.getLong(2)})) {
      cases.put((String) row[0], (Long) row[1]);
    }
    return cases;
  }

  /** bagTag to its legs (legId to flight number) and its latest device sequence, if scanned. */
  private record SourceBag(Map<String, String> legs, Long latestSequence) {}

  private static Map<String, SourceBag> sourceBags() throws Exception {
    Map<String, Map<String, String>> legs = new TreeMap<>();
    for (String[] row :
        owners()
            .query(
                "SELECT b.bag_tag, l.id::text, l.flight_number FROM journey.baggage b"
                    + " JOIN journey.itinerary i ON i.baggage_id = b.id"
                    + " JOIN journey.itinerary_leg l ON l.itinerary_id = i.id",
                r -> new String[] {r.getString(1), r.getString(2), r.getString(3)})) {
      legs.computeIfAbsent(row[0], k -> new TreeMap<>()).put(row[1], row[2]);
    }
    Map<String, Long> latest = new HashMap<>();
    for (Object[] row :
        owners()
            .query(
                "SELECT bag_tag, latest_device_sequence FROM journey.baggage"
                    + " WHERE latest_scan_id IS NOT NULL",
                r -> new Object[] {r.getString(1), r.getLong(2)})) {
      latest.put((String) row[0], (Long) row[1]);
    }
    Map<String, SourceBag> bags = new TreeMap<>();
    legs.forEach((tag, its) -> bags.put(tag, new SourceBag(its, latest.get(tag))));
    return bags;
  }

  /** Every table of both owners: row counts and checksums. */
  private static Map<String, String> sourceFingerprint(Map<String, String> excludingRecovery)
      throws Exception {
    Map<String, String> all = new LinkedHashMap<>();
    owners().fingerprint("journey", Map.of()).forEach((t, f) -> all.put("journey." + t, f));
    owners()
        .fingerprint("recovery", excludingRecovery)
        .forEach((t, f) -> all.put("recovery." + t, f));
    return all;
  }

  // ------------------------------------------------------------------ the search-service

  private String startRebuild() throws Exception {
    String body =
        mockMvc
            .perform(
                post("/api/v1/admin/search/reindex")
                    .param("scope", "ALL")
                    .header("Idempotency-Key", UUID.randomUUID().toString())
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

  private boolean running(String jobId) throws Exception {
    return "RUNNING".equals(job(jobId).get("status").asString());
  }

  private JsonNode finished(String jobId) throws Exception {
    await().atMost(Duration.ofMinutes(3)).until(() -> !running(jobId));
    return job(jobId);
  }

  /** Every case document of the read alias, with its stored version. */
  @SuppressWarnings("rawtypes")
  private List<Hit<Map>> caseHits() throws Exception {
    client.indices().refresh(r -> r.index(IndexFamily.CASES.readAlias()));
    return client
        .search(s -> s.index(IndexFamily.CASES.readAlias()).size(10_000).version(true), Map.class)
        .hits()
        .hits();
  }

  private long storedVersion(String caseId) throws Exception {
    client.indices().refresh(r -> r.index(IndexFamily.CASES.readAlias()));
    return caseHits().stream()
        .filter(h -> h.id().equals(caseId))
        .mapToLong(h -> h.version() == null ? -1 : h.version())
        .findFirst()
        .orElse(-1);
  }

  private void assertNoDuplicateDocIds(String alias) throws Exception {
    var duplicates =
        client.search(
            s ->
                s.index(alias)
                    .size(0)
                    .aggregations(
                        "dup", a -> a.terms(t -> t.field("docId").minDocCount(2).size(10))),
            Map.class);
    assertThat(duplicates.aggregations().get("dup").sterms().buckets().array())
        .as("documents of %s sharing a docId", alias)
        .isEmpty();
  }

  /** The AT-18 conditions for cases, against the recovery-service's own state. */
  @SuppressWarnings("rawtypes")
  private void assertCasesEqualSource(JsonNode results) throws Exception {
    Map<String, Long> source = sourceCases();
    assertThat(source).hasSize(CASES);
    assertThat(results.get("sourceCount").asLong()).isEqualTo(CASES);
    assertThat(results.get("indexedCount").asLong()).isEqualTo(CASES);
    assertThat(results.get("missing").asLong()).isZero();
    List<Hit<Map>> hits = caseHits();
    List<String> ids = hits.stream().map(Hit::id).toList();
    // _count == N, the ids are the source's, no id twice.
    assertThat(client.count(c -> c.index(IndexFamily.CASES.readAlias())).count()).isEqualTo(CASES);
    assertThat(ids).hasSize(CASES).doesNotHaveDuplicates();
    assertThat(new HashSet<>(ids)).isEqualTo(source.keySet());
    assertNoDuplicateDocIds(IndexFamily.CASES.readAlias());
    // Stored versions equal the source versions.
    for (Hit<Map> hit : hits) {
      assertThat(hit.version()).as("version of %s", hit.id()).isEqualTo(source.get(hit.id()));
    }
    // _alias/cases-read names exactly one index.
    assertThat(admin.indexesOf(IndexFamily.CASES.readAlias())).hasSize(1);
  }

  /** Every exported bag has one journey with its legs, airlines and latest scan. */
  @SuppressWarnings({"rawtypes", "unchecked"})
  private Map<String, Map> assertJourneysCoverSource(JsonNode results) throws Exception {
    Map<String, SourceBag> source = sourceBags();
    assertThat(source).hasSize(BAGS);
    assertThat(results.get("sourceCount").asLong()).isEqualTo(BAGS);
    assertThat(results.get("missing").asLong()).isZero();
    assertThat(results.get("sourceChecksum").asString())
        .isEqualTo(results.get("indexChecksum").asString());
    client.indices().refresh(r -> r.index(IndexFamily.JOURNEYS.readAlias()));
    var found =
        client.mget(
            m -> m.index(IndexFamily.JOURNEYS.readAlias()).ids(new ArrayList<>(source.keySet())),
            Map.class);
    Map<String, Map> journeys = new TreeMap<>();
    for (var doc : found.docs()) {
      assertThat(doc.result().found()).as("journey of %s", doc.result().id()).isTrue();
      journeys.put(doc.result().id(), doc.result().source());
    }
    source.forEach(
        (tag, bag) -> {
          Map journey = journeys.get(tag);
          Map<String, String> airlines = new TreeMap<>();
          for (Map leg : (List<Map>) journey.get("legs")) {
            airlines.put((String) leg.get("legId"), (String) leg.get("airline"));
          }
          // Every leg, with the airline the journey-service decided (the carrier prefix).
          Map<String, String> expected = new TreeMap<>();
          bag.legs().forEach((legId, flight) -> expected.put(legId, flight.substring(0, 2)));
          assertThat(airlines).as("legs of %s", tag).isEqualTo(expected);
          Map latest = (Map) journey.get("latestScan");
          if (bag.latestSequence() == null) {
            assertThat(latest).as("latest scan of %s", tag).isNull();
          } else {
            assertThat(((Number) latest.get("deviceSequence")).longValue())
                .as("latest scan of %s", tag)
                .isEqualTo(bag.latestSequence());
          }
        });
    assertNoDuplicateDocIds(IndexFamily.JOURNEYS.readAlias());
    assertThat(admin.indexesOf(IndexFamily.JOURNEYS.readAlias())).hasSize(1);
    return journeys;
  }

  // ------------------------------------------------------------------ the tests

  @Test
  @DisplayName(
      "AT-18 end to end: a rebuild from the real recovery- and journey-service exports gives"
          + " count, ids and versions equal to the source, no duplicates, one read index, a racing"
          + " stale event loses, a second run is the same, and the source is unchanged")
  @SuppressWarnings("rawtypes")
  void at18RebuildFromTheRealOwners() throws Exception {
    List<SeededCase> seeded = seedCases();
    seedBags();
    owners().awaitOutboxesDrained();
    assertThat(seeded.stream().map(SeededCase::version).distinct())
        .containsExactlyInAnyOrder(1L, 2L, 3L, 5L);
    // A resolved case gets stale events during the build; an open one is moved by its owner.
    SeededCase staleTarget = seeded.get(3);
    SeededCase movedByOwner = seeded.get(0);
    assertThat(staleTarget.version()).isEqualTo(5);
    Map<String, String> excludingTheMove =
        Map.of(
            "recovery_case", "id = '" + movedByOwner.caseId() + "'::uuid",
            "state_transition", "case_id = '" + movedByOwner.caseId() + "'::uuid",
            "outbox_event", "message_key = '" + movedByOwner.caseId() + "'");
    Map<String, String> beforeFirst = sourceFingerprint(excludingTheMove);
    String readBefore = admin.readIndex(IndexFamily.CASES);

    // Run 1, with a race: stale events keep arriving and the owner moves a case meanwhile.
    String first = startRebuild();
    AtomicBoolean stop = new AtomicBoolean();
    AtomicInteger staleWrites = new AtomicInteger();
    AtomicReference<Throwable> racerFailure = new AtomicReference<>();
    Thread racer =
        Thread.ofVirtual()
            .start(
                () -> {
                  try {
                    move(movedByOwner.caseId(), RecoveryState.ASSESSING, 1);
                    CaseChangedPayload stale = staleOf(staleTarget.caseId());
                    while (!stop.get()) {
                      indexer.indexCases(
                          List.of(
                              Events.caseRecord(
                                  Events.caseV2(stale, Instant.now(), "e2e-race-stale"))));
                      staleWrites.incrementAndGet();
                      Thread.sleep(20);
                    }
                  } catch (Throwable e) {
                    racerFailure.set(e);
                  }
                });
    JsonNode one = finished(first);
    stop.set(true);
    racer.join(Duration.ofSeconds(30));

    assertThat(racerFailure.get()).isNull();
    assertThat(one.get("status").asString()).as("job %s", one).isEqualTo("SUCCEEDED");
    assertThat(staleWrites.get()).isPositive();
    assertThat(admin.readIndex(IndexFamily.CASES)).isNotEqualTo(readBefore);
    // The owner's own move reaches the new index through its event (live writes go to both).
    owners().awaitOutboxesDrained();
    await().atMost(Duration.ofSeconds(60)).until(() -> storedVersion(movedByOwner.caseId()) == 2);
    assertThat(storedVersion(staleTarget.caseId())).isEqualTo(5);
    assertCasesEqualSource(one.get("results").get("cases"));
    Map<String, Map> journeysAfterFirst =
        assertJourneysCoverSource(one.get("results").get("journeys"));
    // The rebuild wrote nothing at the source: only the owner's own move changed it.
    assertThat(sourceFingerprint(excludingTheMove)).isEqualTo(beforeFirst);

    // Run 2, quiet: the same result, and not a single row of the source changes.
    Map<String, String> beforeSecond = sourceFingerprint(Map.of());
    String readAfterFirst = admin.readIndex(IndexFamily.CASES);
    JsonNode two = finished(startRebuild());

    assertThat(two.get("status").asString()).as("job %s", two).isEqualTo("SUCCEEDED");
    assertThat(admin.readIndex(IndexFamily.CASES)).isNotEqualTo(readAfterFirst);
    assertCasesEqualSource(two.get("results").get("cases"));
    assertThat(two.get("results").get("cases").get("sourceChecksum").asString())
        .isEqualTo(two.get("results").get("cases").get("indexChecksum").asString())
        .isEqualTo(one.get("results").get("cases").get("sourceChecksum").asString());
    Map<String, Map> journeysAfterSecond =
        assertJourneysCoverSource(two.get("results").get("journeys"));
    assertThat(journeysAfterSecond.keySet()).isEqualTo(journeysAfterFirst.keySet());
    journeysAfterSecond.forEach(
        (tag, journey) ->
            assertThat(journey.get("legs"))
                .as("legs of %s", tag)
                .isEqualTo(journeysAfterFirst.get(tag).get("legs")));
    assertThat(sourceFingerprint(Map.of())).isEqualTo(beforeSecond);
  }

  @Test
  @DisplayName(
      "the real owners refuse their export without the service token, with a wrong one and with a"
          + " user token")
  void theRealOwnersRequireTheServiceToken() throws Exception {
    for (String url :
        List.of(owners().recoveryUrl() + ExportApi.CASES, owners().journeyUrl() + ExportApi.BAGS)) {
      assertThat(owners().status(url, Map.of())).as(url).isEqualTo(401);
      assertThat(owners().status(url, Map.of(ServiceToken.HEADER, "wrong-but-long-enough-token")))
          .as(url)
          .isEqualTo(401);
      assertThat(owners().status(url, Map.of("Authorization", "Bearer " + owners().token("ADMIN"))))
          .as(url)
          .isEqualTo(401);
      assertThat(owners().status(url, Map.of(ServiceToken.HEADER, RealOwners.SERVICE_TOKEN)))
          .as(url)
          .isEqualTo(200);
    }
  }

  /** The case one version older than the owner has it: what a late, replayed event carries. */
  private static CaseChangedPayload staleOf(String caseId) {
    try {
      return owners()
          .query(
              "SELECT id::text, bag_tag, leg_id, version, created_at FROM recovery.recovery_case"
                  + " WHERE id = '"
                  + caseId
                  + "'::uuid",
              r ->
                  new CaseChangedPayload(
                      r.getString(1),
                      r.getString(2),
                      r.getString(3),
                      RecoveryState.IN_TRANSIT,
                      r.getLong(4) - 1,
                      "stale",
                      r.getTimestamp(5).toInstant(),
                      null,
                      null,
                      null))
          .getFirst();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
