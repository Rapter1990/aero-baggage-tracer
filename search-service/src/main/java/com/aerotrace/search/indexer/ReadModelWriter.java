package com.aerotrace.search.indexer;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.Conflicts;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.Script;
import co.elastic.clients.elasticsearch._types.VersionType;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.MgetResponse;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.bulk.BulkOperation;
import co.elastic.clients.elasticsearch.core.mget.MultiGetResponseItem;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.json.JsonData;
import com.aerotrace.common.model.RecoveryState;
import com.aerotrace.search.elastic.Elastic;
import com.aerotrace.search.elastic.IndexAdmin;
import com.aerotrace.search.elastic.IndexFamily;
import com.aerotrace.search.elastic.SupportIndex;
import com.aerotrace.search.indexer.BulkExecutor.Ack;
import com.aerotrace.search.indexer.BulkExecutor.Op;
import com.aerotrace.search.indexer.model.CaseChange;
import com.aerotrace.search.indexer.model.LegSnapshot;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Writes the read models, for the live indexer and the rebuild alike, into the indexes it is given.
 * Every write is idempotent:
 *
 * <ul>
 *   <li>a case is indexed with {@code version_type=external} and its own version, so the same or an
 *       older version is refused by Elasticsearch with 409, which is a success here ({@link
 *       Outcome#DUPLICATE} or {@link Outcome#STALE}), never an error;
 *   <li>a journey is a scripted upsert guarded per part (see {@link JourneyChange});
 *   <li>legs and risk hours have deterministic ids.
 * </ul>
 */
@Component
public class ReadModelWriter {

  /** What became of one input: an outcome, or the reason Elasticsearch refused it for good. */
  public record ItemResult(Outcome outcome, String rejection) {

    static ItemResult rejected(String reason) {
      return new ItemResult(null, reason);
    }

    public boolean rejected() {
      return outcome == null;
    }
  }

  /** A case and the time of the change (the event time, or null for an export snapshot). */
  public record CaseWrite(CaseChange change, Instant changedAt) {}

  /** A HIGH risk of a leg at a moment. */
  public record RiskHour(String bagTag, String legId, Instant occurredAt) {}

  private static final Pattern VERSIONS =
      Pattern.compile(
          "current version \\[(\\d+)] is higher or equal to the one provided \\[(\\d+)]");
  private static final int RETRY_ON_CONFLICT = 5;
  private static final int COMPLETION_PAGE = 1_000;

  private final ElasticsearchClient client;
  private final BulkExecutor bulk;
  private final IndexAdmin admin;

  public ReadModelWriter(ElasticsearchClient client, BulkExecutor bulk, IndexAdmin admin) {
    this.client = client;
    this.bulk = bulk;
    this.admin = admin;
  }

  // ------------------------------------------------------------------ cases

  /** Indexes cases into every target; one result per case. */
  public List<ItemResult> writeCases(List<CaseWrite> cases, List<String> targets)
      throws IOException {
    if (cases.isEmpty()) {
      return List.of();
    }
    Map<String, LegSnapshot> legs = legsById(cases.stream().map(c -> c.change().legId()).toList());
    List<Op> ops = new ArrayList<>();
    for (int i = 0; i < cases.size(); i++) {
      CaseWrite write = cases.get(i);
      Map<String, Object> doc =
          caseDocument(write.change(), write.changedAt(), legs.get(write.change().legId()));
      for (String target : targets) {
        ops.add(new Op(i, indexCase(target, write.change(), doc, VersionType.External)));
      }
    }
    List<ItemResult> results = combine(cases.size(), bulk.execute(ops));
    completeCasesWhoseLegArrivedMeanwhile(cases, legs, results, targets);
    return results;
  }

  /**
   * A case written without its leg (the leg was not known yet) is written again, with the same
   * version, if the leg became known while it was being written. Together with {@link
   * #completeLegs} this closes the race between the two topics: either the leg writer finds the
   * case, or this check finds the leg.
   */
  private void completeCasesWhoseLegArrivedMeanwhile(
      List<CaseWrite> cases,
      Map<String, LegSnapshot> knownBefore,
      List<ItemResult> results,
      List<String> targets)
      throws IOException {
    Set<String> missing = new LinkedHashSet<>();
    for (int i = 0; i < cases.size(); i++) {
      String legId = cases.get(i).change().legId();
      if (results.get(i).outcome() == Outcome.INDEXED && !knownBefore.containsKey(legId)) {
        missing.add(legId);
      }
    }
    if (missing.isEmpty()) {
      return;
    }
    Map<String, LegSnapshot> now = legsById(missing);
    if (now.isEmpty()) {
      return;
    }
    List<Op> ops = new ArrayList<>();
    for (int i = 0; i < cases.size(); i++) {
      CaseWrite write = cases.get(i);
      LegSnapshot leg = now.get(write.change().legId());
      if (leg != null && results.get(i).outcome() == Outcome.INDEXED) {
        Map<String, Object> doc = caseDocument(write.change(), write.changedAt(), leg);
        for (String target : targets) {
          ops.add(new Op(i, indexCase(target, write.change(), doc, VersionType.ExternalGte)));
        }
      }
    }
    // 409 here means a newer version arrived in between; it was written with the leg already.
    bulk.execute(ops);
  }

  private static BulkOperation indexCase(
      String target, CaseChange change, Map<String, Object> doc, VersionType type) {
    return BulkOperation.of(
        b ->
            b.index(
                i ->
                    i.index(target)
                        .id(change.caseId())
                        .version(change.version())
                        .versionType(type)
                        .document(doc)));
  }

  static Map<String, Object> caseDocument(CaseChange c, Instant changedAt, LegSnapshot leg) {
    Map<String, Object> doc = new LinkedHashMap<>();
    doc.put("docId", c.caseId());
    doc.put("caseId", c.caseId());
    doc.put("bagTag", c.bagTag());
    doc.put("legId", c.legId());
    doc.put("state", c.state().name());
    doc.put("trigger", c.trigger());
    doc.put("version", c.version());
    doc.put("reason", c.reason());
    doc.put("openedAt", Elastic.millis(c.openedAt()));
    doc.put("resolvedAt", Elastic.millis(c.resolvedAt()));
    Instant updatedAt = changedAt != null ? changedAt : latest(c.openedAt(), c.resolvedAt());
    doc.put("updatedAt", Elastic.millis(updatedAt));
    if (c.state() == RecoveryState.RESOLVED && c.openedAt() != null && c.resolvedAt() != null) {
      doc.put("resolutionSeconds", Duration.between(c.openedAt(), c.resolvedAt()).toSeconds());
    }
    if (leg != null) {
      // The airport of a case is the origin of its leg (ADR 0004, decision 7).
      doc.put("airport", leg.origin());
      doc.put("airline", leg.airline());
      doc.put("flightNumber", leg.flightNumber());
    }
    doc.values().removeIf(Objects::isNull);
    return doc;
  }

  private static Instant latest(Instant a, Instant b) {
    if (a == null) {
      return b;
    }
    return b == null || a.isAfter(b) ? a : b;
  }

  /** Per input: indexed if any target took it, else what the 409s said, else refused. */
  private static List<ItemResult> combine(int size, List<Ack> acks) {
    Outcome[] outcomes = new Outcome[size];
    String[] rejections = new String[size];
    for (Ack ack : acks) {
      Outcome current = outcomes[ack.item()];
      if (ack.ok()) {
        outcomes[ack.item()] =
            "noop".equals(ack.result()) && current != Outcome.INDEXED
                ? Outcome.STALE
                : Outcome.INDEXED;
      } else if (ack.conflict()) {
        if (current == null) {
          outcomes[ack.item()] = conflictOutcome(ack.errorReason());
        }
      } else if (current == null) {
        rejections[ack.item()] = ack.errorType() == null ? "rejected" : ack.errorType();
      }
    }
    List<ItemResult> results = new ArrayList<>(size);
    for (int i = 0; i < size; i++) {
      results.add(
          outcomes[i] != null
              ? new ItemResult(outcomes[i], null)
              : ItemResult.rejected(rejections[i] == null ? "rejected" : rejections[i]));
    }
    return results;
  }

  static Outcome conflictOutcome(String reason) {
    if (reason != null) {
      Matcher versions = VERSIONS.matcher(reason);
      if (versions.find() && versions.group(1).equals(versions.group(2))) {
        return Outcome.DUPLICATE;
      }
    }
    return Outcome.STALE;
  }

  // ------------------------------------------------------------------ journeys

  /** Applies journey changes to every target; one result per change. */
  public List<ItemResult> applyJourneys(List<JourneyChange> changes, List<String> targets)
      throws IOException {
    if (changes.isEmpty()) {
      return List.of();
    }
    List<Op> ops = new ArrayList<>();
    for (int i = 0; i < changes.size(); i++) {
      JourneyChange change = changes.get(i);
      Map<String, JsonData> params = new HashMap<>();
      change.params().forEach((key, value) -> params.put(key, JsonData.of(value)));
      Script script =
          Script.of(s -> s.source(src -> src.scriptString(JourneyChange.SCRIPT)).params(params));
      for (String target : targets) {
        ops.add(
            new Op(
                i,
                BulkOperation.of(
                    b ->
                        b.update(
                            u ->
                                u.index(target)
                                    .id(change.bagTag())
                                    .retryOnConflict(RETRY_ON_CONFLICT)
                                    .action(
                                        a ->
                                            a.script(script)
                                                .scriptedUpsert(true)
                                                .upsert(Map.of()))))));
      }
    }
    return combine(changes.size(), bulk.execute(ops));
  }

  // ------------------------------------------------------------------ legs and risk hours

  /** The leg lookup for these legs (realtime: what was just written is seen). */
  @SuppressWarnings({"rawtypes", "unchecked"})
  public Map<String, LegSnapshot> legsById(Collection<String> legIds) throws IOException {
    List<String> ids = legIds.stream().filter(Objects::nonNull).distinct().toList();
    if (ids.isEmpty()) {
      return Map.of();
    }
    MgetResponse<Map> response =
        client.mget(m -> m.index(SupportIndex.LEGS.index()).ids(ids), Map.class);
    Map<String, LegSnapshot> legs = new HashMap<>();
    for (MultiGetResponseItem<Map> item : response.docs()) {
      if (item.isResult() && item.result().found()) {
        Map<String, Object> source = item.result().source();
        legs.put(
            item.result().id(),
            new LegSnapshot(
                (String) source.get("legId"),
                (String) source.get("flightNumber"),
                (String) source.get("airline"),
                (String) source.get("origin"),
                (String) source.get("destination"),
                Elastic.instant(source.get("departureAt"))));
      }
    }
    return legs;
  }

  /**
   * Stores legs in the lookup and completes what was indexed before they were known: the cases and
   * risk hours of these legs that have no airport yet.
   *
   * @param legs the legs by id
   * @param bagOfLeg the bag of each leg
   */
  public void writeLegs(Map<String, LegSnapshot> legs, Map<String, String> bagOfLeg)
      throws IOException {
    if (legs.isEmpty()) {
      return;
    }
    List<Op> ops = new ArrayList<>();
    int i = 0;
    for (LegSnapshot leg : legs.values()) {
      Map<String, Object> doc = new LinkedHashMap<>();
      doc.put("legId", leg.legId());
      doc.put("bagTag", bagOfLeg.get(leg.legId()));
      doc.put("flightNumber", leg.flightNumber());
      doc.put("airline", leg.airline());
      doc.put("origin", leg.origin());
      doc.put("destination", leg.destination());
      doc.put("departureAt", Elastic.millis(leg.departureAt()));
      doc.values().removeIf(Objects::isNull);
      ops.add(
          new Op(
              i++,
              BulkOperation.of(
                  b ->
                      b.index(
                          x -> x.index(SupportIndex.LEGS.index()).id(leg.legId()).document(doc)))));
    }
    for (Ack ack : bulk.execute(ops)) {
      if (!ack.ok()) {
        throw new PoisonEventException("leg refused: " + ack.errorType());
      }
    }
    completeLegs(legs);
  }

  private void completeLegs(Map<String, LegSnapshot> legs) throws IOException {
    List<FieldValue> ids = legs.keySet().stream().map(FieldValue::of).toList();
    Query withoutAirport =
        Query.of(
            q ->
                q.bool(
                    b ->
                        b.filter(f -> f.terms(t -> t.field("legId").terms(v -> v.value(ids))))
                            .mustNot(m -> m.exists(e -> e.field("airport")))));
    // Cases: written again with their own version (external_gte), so the version is unchanged
    // and a newer event still wins. (_update_by_query would raise the internal version and make
    // the next real event look stale.)
    for (String index : admin.liveIndexes(IndexFamily.CASES)) {
      admin.refresh(index);
      completeCases(index, withoutAirport, legs);
    }
    // Risk hours have no version: an update by query is enough.
    Map<String, JsonData> airports = new HashMap<>();
    legs.forEach(
        (id, leg) -> {
          if (leg.origin() != null) {
            airports.put(id, JsonData.of(leg.origin()));
          }
        });
    if (!airports.isEmpty()) {
      admin.refresh(SupportIndex.RISK_HOURS.index());
      client.updateByQuery(
          u ->
              u.index(SupportIndex.RISK_HOURS.index())
                  .query(withoutAirport)
                  .conflicts(Conflicts.Proceed)
                  .script(
                      s ->
                          s.source(
                                  src ->
                                      src.scriptString(
                                          "def a = params.airports[ctx._source.legId];"
                                              + " if (a == null) { ctx.op = 'noop'; }"
                                              + " else { ctx._source.airport = a; }"))
                              .params("airports", JsonData.of(toPlain(airports)))));
    }
  }

  private static Map<String, Object> toPlain(Map<String, JsonData> values) {
    Map<String, Object> plain = new HashMap<>();
    values.forEach((key, value) -> plain.put(key, value.to(String.class)));
    return plain;
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  private void completeCases(String index, Query withoutAirport, Map<String, LegSnapshot> legs)
      throws IOException {
    SearchResponse<Map> page =
        client.search(
            s -> s.index(index).query(withoutAirport).size(COMPLETION_PAGE).version(true),
            Map.class);
    List<Op> ops = new ArrayList<>();
    int i = 0;
    for (Hit<Map> hit : page.hits().hits()) {
      Map<String, Object> doc = new LinkedHashMap<>(hit.source());
      LegSnapshot leg = legs.get((String) doc.get("legId"));
      if (leg == null || leg.origin() == null) {
        continue;
      }
      doc.put("airport", leg.origin());
      if (leg.airline() != null) {
        doc.put("airline", leg.airline());
      }
      if (leg.flightNumber() != null) {
        doc.put("flightNumber", leg.flightNumber());
      }
      long version = hit.version();
      ops.add(
          new Op(
              i++,
              BulkOperation.of(
                  b ->
                      b.index(
                          x ->
                              x.index(index)
                                  .id(hit.id())
                                  .version(version)
                                  .versionType(VersionType.ExternalGte)
                                  .document(doc)))));
    }
    bulk.execute(ops);
  }

  /** Records HIGH risk hours: one document per bag, leg and UTC hour. */
  public void writeRiskHours(List<RiskHour> hours) throws IOException {
    if (hours.isEmpty()) {
      return;
    }
    Map<String, LegSnapshot> legs = legsById(hours.stream().map(RiskHour::legId).toList());
    List<Op> ops = new ArrayList<>();
    int i = 0;
    for (RiskHour hour : hours) {
      Instant start = hour.occurredAt().truncatedTo(ChronoUnit.HOURS);
      LegSnapshot leg = legs.get(hour.legId());
      Map<String, Object> doc = new LinkedHashMap<>();
      doc.put("bagTag", hour.bagTag());
      doc.put("legId", hour.legId());
      doc.put("airport", leg == null ? null : leg.origin());
      doc.put("hour", Elastic.millis(start));
      doc.put("occurredAt", Elastic.millis(hour.occurredAt()));
      doc.values().removeIf(Objects::isNull);
      String id = hour.bagTag() + ":" + hour.legId() + ":" + start.toEpochMilli();
      ops.add(
          new Op(
              i++,
              BulkOperation.of(
                  b ->
                      b.index(
                          x -> x.index(SupportIndex.RISK_HOURS.index()).id(id).document(doc)))));
    }
    for (Ack ack : bulk.execute(ops)) {
      if (!ack.ok()) {
        throw new PoisonEventException("risk hour refused: " + ack.errorType());
      }
    }
  }
}
