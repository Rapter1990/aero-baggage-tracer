package com.aerotrace.search.search;

import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import com.aerotrace.search.config.SearchProperties;
import com.aerotrace.search.document.JourneyDocument;
import com.aerotrace.search.document.JourneyDocument.LatestScan;
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
import org.springframework.data.elasticsearch.client.elc.NativeQuery;
import org.springframework.data.elasticsearch.client.elc.NativeQueryBuilder;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.data.elasticsearch.core.mapping.IndexCoordinates;
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

  private final ElasticsearchOperations operations;
  private final CursorCodec cursors;
  private final SearchProperties.Index index;
  private final Clock clock;

  public BagSearchService(
      ElasticsearchOperations operations,
      CursorCodec cursors,
      SearchProperties properties,
      Clock clock) {
    this.operations = operations;
    this.cursors = cursors;
    this.index = properties.index();
    this.clock = clock;
  }

  public Page search(Criteria criteria, int size, String cursorToken) {
    String fingerprint = criteria.fingerprint();
    Cursor cursor = cursorToken == null ? null : cursors.decode(cursorToken, fingerprint);
    String pit = cursor != null ? cursor.pit() : null;
    NativeQueryBuilder request =
        NativeQuery.builder()
            .withQuery(query(criteria))
            .withMaxResults(size + 1)
            .withTrackTotalHits(false)
            .withSort(
                o -> o.field(f -> f.field("updatedAt").order(SortOrder.Desc).missing("_last")))
            .withSort(o -> o.field(f -> f.field("docId").order(SortOrder.Asc)));
    if (pit != null) {
      request.withPointInTime(
          new org.springframework.data.elasticsearch.core.query.Query.PointInTime(
              pit, index.pitKeepAlive()));
    }
    if (cursor != null && !cursor.after().isEmpty()) {
      request.withSearchAfter(cursor.after().stream().map(BagSearchService::value).toList());
    }
    SearchHits<JourneyDocument> response;
    try {
      // Without a point-in-time the search reads the repository's index, the journeys-read alias.
      response = Elastic.call(() -> operations.search(request.build(), JourneyDocument.class));
    } catch (RuntimeException e) {
      if (cursor != null && Integer.valueOf(404).equals(Elastic.status(e))) {
        // The point-in-time is gone (kept alive too long ago, or the node restarted).
        throw CursorCodec.expired();
      }
      throw e;
    }
    List<SearchHit<JourneyDocument>> hits = response.getSearchHits();
    boolean hasMore = hits.size() > size;
    List<SearchHit<JourneyDocument>> page = hasMore ? hits.subList(0, size) : hits;
    List<BagSummary> items = page.stream().map(h -> summary(h.getContent())).toList();
    String next = null;
    String currentPit = response.getPointInTimeId() != null ? response.getPointInTimeId() : pit;
    if (hasMore && currentPit == null) {
      // The first page has a next one: only now is a point-in-time needed.
      currentPit =
          Elastic.call(
              () ->
                  operations.openPointInTime(
                      IndexCoordinates.of(IndexFamily.JOURNEYS.readAlias()), index.pitKeepAlive()));
    }
    if (hasMore) {
      List<Object> sort = new ArrayList<>(page.get(page.size() - 1).getSortValues());
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
      operations.closePointInTime(pit);
    } catch (Exception ignored) {
      // It expires on its own.
    }
  }

  /** A sort value of the cursor (signed, but still checked): a number or text, nothing else. */
  private static Object value(Object value) {
    if (value instanceof Number number) {
      return number.longValue();
    }
    if (value instanceof String text) {
      return text;
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

  private static BagSummary summary(JourneyDocument doc) {
    LatestScan scan = doc.latestScan();
    List<JourneyDocument.Leg> legs = doc.legs() == null ? List.of() : doc.legs();
    return new BagSummary(
        doc.bagTag(),
        doc.updatedAt(),
        scan == null ? null : new ScanSummary(scan.airport(), scan.checkpoint(), scan.occurredAt()),
        legs.stream()
            .map(
                leg ->
                    new LegSummary(
                        leg.legId(),
                        leg.flightNumber(),
                        leg.airline(),
                        leg.origin(),
                        leg.destination(),
                        leg.departureAt(),
                        leg.riskLevel(),
                        leg.caseId(),
                        leg.caseState()))
            .toList());
  }
}
