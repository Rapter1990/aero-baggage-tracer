package com.aerotrace.search.report;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.aggregations.DateHistogramBucket;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.aerotrace.common.model.RecoveryState;
import com.aerotrace.search.elastic.Elastic;
import com.aerotrace.search.elastic.IndexFamily;
import com.aerotrace.search.elastic.SupportIndex;
import com.aerotrace.search.report.AirportReport.Bucket;
import com.aerotrace.search.report.AirportReport.Totals;
import com.aerotrace.search.status.LagMonitor;
import com.aerotrace.search.status.LagMonitor.Lag;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import org.springframework.stereotype.Service;

/**
 * Builds the airport report from documents with deterministic ids, never from approximate
 * aggregations: date histograms count documents exactly, and distinct bags are counted from the
 * risk-hour documents themselves ({@code _id = bag:leg:hour}). The airport of a case or a risk hour
 * is the origin of its leg.
 */
@Service
public class AirportReportService {

  private static final int PAGE = 1_000;
  private static final List<String> FAILED_STATES =
      List.of(RecoveryState.CANCELLED.name(), RecoveryState.CLOSED_AS_UNRECOVERABLE.name());

  private final ElasticsearchClient client;
  private final LagMonitor lag;

  public AirportReportService(ElasticsearchClient client, LagMonitor lag) {
    this.client = client;
    this.lag = lag;
  }

  public AirportReport report(String airport, Instant from, Instant to) {
    int hours = (int) Duration.between(from, to).toHours();
    Map<Instant, Set<String>> risky = new HashMap<>();
    Map<Instant, long[]> resolved = new HashMap<>(); // [count, sum of seconds, timed count]
    Map<Instant, Long> retries = new HashMap<>();
    Elastic.call(
        () -> {
          riskyBags(airport, from, to, risky);
          resolved(airport, from, to, resolved);
          retries(airport, from, to, retries);
          return null;
        });

    List<Bucket> buckets = new ArrayList<>(hours);
    Set<String> allRisky = new HashSet<>();
    long totalResolved = 0;
    long totalSeconds = 0;
    long totalTimed = 0;
    long totalRetries = 0;
    for (int i = 0; i < hours; i++) {
      Instant hour = from.plus(i, ChronoUnit.HOURS);
      Set<String> bags = risky.getOrDefault(hour, Set.of());
      long[] r = resolved.getOrDefault(hour, new long[3]);
      long retried = retries.getOrDefault(hour, 0L);
      buckets.add(new Bucket(hour, bags.size(), r[0], retried, mean(r[1], r[2])));
      allRisky.addAll(bags);
      totalResolved += r[0];
      totalSeconds += r[1];
      totalTimed += r[2];
      totalRetries += retried;
    }
    Lag now = lag.check();
    return new AirportReport(
        airport,
        from,
        to,
        now.indexedThrough(),
        now.lagSeconds(),
        buckets,
        new Totals(allRisky.size(), totalResolved, totalRetries, mean(totalSeconds, totalTimed)));
  }

  private static Double mean(long sum, long count) {
    return count == 0 ? null : (double) sum / count;
  }

  private static Query term(String field, String value) {
    return Query.of(q -> q.term(t -> t.field(field).value(value)));
  }

  private static Query range(String field, Instant from, Instant to) {
    return Query.of(
        q ->
            q.range(
                r ->
                    r.date(
                        d ->
                            d.field(field)
                                .gte(String.valueOf(from.toEpochMilli()))
                                .lt(String.valueOf(to.toEpochMilli()))
                                .format("epoch_millis"))));
  }

  private static Instant hourOf(Object millis) {
    return Elastic.instant(millis).truncatedTo(ChronoUnit.HOURS);
  }

  /**
   * Pages through every hit of a query in a stable order: the sort fields must identify a document
   * (stored keywords, never {@code _id}, which cannot be sorted on).
   */
  @SuppressWarnings({"rawtypes", "unchecked"})
  private void scan(
      String index, Query query, List<String> sortFields, Consumer<Map<String, Object>> each)
      throws IOException {
    List<FieldValue> after = null;
    while (true) {
      List<FieldValue> current = after;
      SearchResponse<Map> page =
          client.search(
              s -> {
                s.index(index).query(query).size(PAGE).trackTotalHits(t -> t.enabled(false));
                for (String field : sortFields) {
                  s.sort(o -> o.field(f -> f.field(field).order(SortOrder.Asc)));
                }
                if (current != null) {
                  s.searchAfter(current);
                }
                return s;
              },
              Map.class);
      List<Hit<Map>> hits = page.hits().hits();
      hits.forEach(hit -> each.accept(hit.source()));
      if (hits.size() < PAGE) {
        return;
      }
      after = hits.getLast().sort();
    }
  }

  /** Distinct bags per hour, from the risk-hour documents. */
  private void riskyBags(String airport, Instant from, Instant to, Map<Instant, Set<String>> risky)
      throws IOException {
    Query query =
        Query.of(
            q -> q.bool(b -> b.filter(term("airport", airport)).filter(range("hour", from, to))));
    scan(
        SupportIndex.RISK_HOURS.index(),
        query,
        List.of("hour", "bagTag", "legId"),
        doc ->
            risky
                .computeIfAbsent(hourOf(doc.get("hour")), h -> new HashSet<>())
                .add((String) doc.get("bagTag")));
  }

  /** Resolved cases per hour of resolvedAt: an exact date histogram with the sum of seconds. */
  private void resolved(String airport, Instant from, Instant to, Map<Instant, long[]> resolved)
      throws IOException {
    Query query =
        Query.of(
            q ->
                q.bool(
                    b ->
                        b.filter(term("airport", airport))
                            .filter(term("state", RecoveryState.RESOLVED.name()))
                            .filter(range("resolvedAt", from, to))));
    SearchResponse<Void> response =
        client.search(
            s ->
                s.index(IndexFamily.CASES.readAlias())
                    .query(query)
                    .size(0)
                    .aggregations(
                        "hours",
                        a ->
                            a.dateHistogram(
                                    d -> d.field("resolvedAt").fixedInterval(t -> t.time("1h")))
                                .aggregations(
                                    "seconds", x -> x.sum(m -> m.field("resolutionSeconds")))
                                .aggregations(
                                    "timed", x -> x.valueCount(m -> m.field("resolutionSeconds")))),
            Void.class);
    for (DateHistogramBucket bucket :
        response.aggregations().get("hours").dateHistogram().buckets().array()) {
      if (bucket.docCount() == 0) {
        continue;
      }
      long seconds = Math.round(bucket.aggregations().get("seconds").sum().value());
      long timed = Math.round(bucket.aggregations().get("timed").valueCount().value());
      resolved.put(
          Instant.ofEpochMilli(bucket.key()), new long[] {bucket.docCount(), seconds, timed});
    }
  }

  /**
   * Cases opened in the range for a bag and leg that had an earlier case (opened before it) which
   * ended CANCELLED or CLOSED_AS_UNRECOVERABLE, counted in the hour of their openedAt.
   */
  private void retries(String airport, Instant from, Instant to, Map<Instant, Long> retries)
      throws IOException {
    List<Map<String, Object>> opened = new ArrayList<>();
    Query query =
        Query.of(
            q ->
                q.bool(
                    b -> b.filter(term("airport", airport)).filter(range("openedAt", from, to))));
    scan(IndexFamily.CASES.readAlias(), query, List.of("openedAt", "docId"), opened::add);
    if (opened.isEmpty()) {
      return;
    }
    // Earliest failure per bag and leg, among the bags of the opened cases.
    Map<String, Long> earliestFailure = new HashMap<>();
    List<String> bagTags = opened.stream().map(c -> (String) c.get("bagTag")).distinct().toList();
    for (int i = 0; i < bagTags.size(); i += PAGE) {
      List<FieldValue> chunk =
          bagTags.subList(i, Math.min(i + PAGE, bagTags.size())).stream()
              .map(FieldValue::of)
              .toList();
      Query failed =
          Query.of(
              q ->
                  q.bool(
                      b ->
                          b.filter(f -> f.terms(t -> t.field("bagTag").terms(v -> v.value(chunk))))
                              .filter(
                                  f ->
                                      f.terms(
                                          t ->
                                              t.field("state")
                                                  .terms(
                                                      v ->
                                                          v.value(
                                                              FAILED_STATES.stream()
                                                                  .map(FieldValue::of)
                                                                  .toList()))))
                              .filter(f -> f.exists(e -> e.field("openedAt")))));
      scan(
          IndexFamily.CASES.readAlias(),
          failed,
          List.of("openedAt", "docId"),
          doc ->
              earliestFailure.merge(
                  doc.get("bagTag") + ":" + doc.get("legId"),
                  ((Number) doc.get("openedAt")).longValue(),
                  Math::min));
    }
    for (Map<String, Object> c : opened) {
      Long failure = earliestFailure.get(c.get("bagTag") + ":" + c.get("legId"));
      long openedAt = ((Number) c.get("openedAt")).longValue();
      if (failure != null && failure < openedAt) {
        retries.merge(hourOf(openedAt), 1L, Long::sum);
      }
    }
  }
}
