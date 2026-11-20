package com.aerotrace.search.search;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.aerotrace.search.config.SearchProperties;
import com.aerotrace.search.elastic.Elastic;
import com.aerotrace.search.elastic.IndexFamily;
import com.aerotrace.search.search.BagSearchModels.BagSummary;
import com.aerotrace.search.search.BagSearchModels.Criteria;
import com.aerotrace.search.search.BagSearchModels.LegSummary;
import com.aerotrace.search.search.BagSearchModels.Page;
import com.aerotrace.search.search.BagSearchModels.ScanSummary;
import com.aerotrace.search.search.CursorCodec.Cursor;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * Bag search over the {@code journeys-read} alias with {@code search_after}. The first page is a
 * plain search of the alias; only when there is a next page is a point-in-time opened (with a short
 * keep-alive, renewed by each page), so the pages after the first see one snapshot even while the
 * index changes or is swapped by a rebuild, and a search that fits one page, the usual case, holds
 * no search context at all (open points-in-time are a limited cluster resource). The snapshot is
 * taken just after the first page, so a document that changed in between can move across the first
 * page's boundary; the pages after it have no duplicates and no gaps. The order is {@code
 * updatedAt} descending, then the stored {@code docId} (never {@code _id}, which cannot be sorted
 * on efficiently).
 */
@Service
public class BagSearchService {

  private final ElasticsearchClient client;
  private final CursorCodec cursors;
  private final SearchProperties.Index index;
  private final Clock clock;

  public BagSearchService(
      ElasticsearchClient client, CursorCodec cursors, SearchProperties properties, Clock clock) {
    this.client = client;
    this.cursors = cursors;
    this.index = properties.index();
    this.clock = clock;
  }

  public Page search(Criteria criteria, int size, String cursorToken) {
    String fingerprint = criteria.fingerprint();
    Cursor cursor = cursorToken == null ? null : cursors.decode(cursorToken, fingerprint);
    String keepAlive = index.pitKeepAlive().toSeconds() + "s";
    String pit = cursor != null ? cursor.pit() : null;
    List<FieldValue> after =
        cursor == null ? List.of() : cursor.after().stream().map(BagSearchService::value).toList();
    Query query = query(criteria);
    SearchResponse<Map> response;
    try {
      response =
          Elastic.call(
              () ->
                  client.search(
                      s -> {
                        if (pit != null) {
                          s.pit(p -> p.id(pit).keepAlive(t -> t.time(keepAlive)));
                        } else {
                          s.index(IndexFamily.JOURNEYS.readAlias());
                        }
                        s.query(query)
                            .size(size + 1)
                            .trackTotalHits(t -> t.enabled(false))
                            .sort(
                                o ->
                                    o.field(
                                        f ->
                                            f.field("updatedAt")
                                                .order(SortOrder.Desc)
                                                .missing("_last")))
                            .sort(o -> o.field(f -> f.field("docId").order(SortOrder.Asc)));
                        if (!after.isEmpty()) {
                          s.searchAfter(after);
                        }
                        return s;
                      },
                      Map.class));
    } catch (ElasticsearchException e) {
      if (cursor != null && e.status() == 404) {
        // The point-in-time is gone (kept alive too long ago, or the node restarted).
        throw CursorCodec.expired();
      }
      throw e;
    }
    List<Hit<Map>> hits = response.hits().hits();
    boolean hasMore = hits.size() > size;
    List<Hit<Map>> page = hasMore ? hits.subList(0, size) : hits;
    List<BagSummary> items = page.stream().map(BagSearchService::summary).toList();
    String next = null;
    String currentPit = response.pitId() != null ? response.pitId() : pit;
    if (hasMore && currentPit == null) {
      // The first page has a next one: only now is a point-in-time needed.
      currentPit =
          Elastic.call(
              () ->
                  client
                      .openPointInTime(
                          p ->
                              p.index(IndexFamily.JOURNEYS.readAlias())
                                  .keepAlive(t -> t.time(keepAlive)))
                      .id());
    }
    if (hasMore) {
      List<Object> sort = new ArrayList<>();
      for (FieldValue value : page.get(page.size() - 1).sort()) {
        sort.add(value._get());
      }
      next =
          cursors.encode(
              new Cursor(
                  currentPit,
                  sort,
                  fingerprint,
                  clock.instant().plus(index.pitKeepAlive()).toEpochMilli()));
    } else if (currentPit != null) {
      close(currentPit);
    }
    return new Page(items, size, next, hasMore);
  }

  private void close(String pit) {
    try {
      client.closePointInTime(c -> c.id(pit));
    } catch (Exception ignored) {
      // It expires on its own.
    }
  }

  private static FieldValue value(Object value) {
    if (value instanceof Number number) {
      return FieldValue.of(number.longValue());
    }
    if (value instanceof String text) {
      return FieldValue.of(text);
    }
    throw CursorCodec.invalid();
  }

  static Query query(Criteria c) {
    List<Query> filters = new ArrayList<>();
    if (c.bagTag() != null) {
      filters.add(term("bagTag", c.bagTag()));
    }
    if (c.airport() != null) {
      filters.add(
          Query.of(
              q ->
                  q.bool(
                      b ->
                          b.should(term("latestScan.airport", c.airport()))
                              .should(leg(term("legs.origin", c.airport())))
                              .should(leg(term("legs.destination", c.airport())))
                              .minimumShouldMatch("1"))));
    }
    if (c.airline() != null) {
      filters.add(leg(term("legs.airline", c.airline())));
    }
    if (c.flight() != null) {
      filters.add(leg(term("legs.flightNumber", c.flight())));
    }
    if (c.riskLevel() != null) {
      filters.add(leg(term("legs.riskLevel", c.riskLevel().name())));
    }
    if (c.caseState() != null) {
      filters.add(leg(term("legs.caseState", c.caseState().name())));
    }
    if (filters.isEmpty()) {
      return Query.of(q -> q.matchAll(m -> m));
    }
    return Query.of(q -> q.bool(b -> b.filter(filters)));
  }

  private static Query term(String field, String value) {
    return Query.of(q -> q.term(t -> t.field(field).value(value)));
  }

  private static Query leg(Query inner) {
    return Query.of(q -> q.nested(n -> n.path("legs").query(inner)));
  }

  @SuppressWarnings("unchecked")
  private static BagSummary summary(Hit<Map> hit) {
    Map<String, Object> source = hit.source();
    Map<String, Object> scan = (Map<String, Object>) source.get("latestScan");
    List<Map<String, Object>> legs =
        (List<Map<String, Object>>) source.getOrDefault("legs", List.of());
    return new BagSummary(
        (String) source.get("bagTag"),
        Elastic.instant(source.get("updatedAt")),
        scan == null
            ? null
            : new ScanSummary(
                (String) scan.get("airport"),
                (String) scan.get("checkpoint"),
                Elastic.instant(scan.get("occurredAt"))),
        legs.stream()
            .map(
                leg ->
                    new LegSummary(
                        (String) leg.get("legId"),
                        (String) leg.get("flightNumber"),
                        (String) leg.get("airline"),
                        (String) leg.get("origin"),
                        (String) leg.get("destination"),
                        Elastic.instant(leg.get("departureAt")),
                        (String) leg.get("riskLevel"),
                        (String) leg.get("caseId"),
                        (String) leg.get("caseState")))
            .toList());
  }
}
