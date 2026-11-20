package com.aerotrace.search.indexer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.aerotrace.common.event.Topics;
import com.aerotrace.common.model.RecoveryState;
import com.aerotrace.common.model.RiskLevel;
import com.aerotrace.search.base.AbstractSearchTest;
import com.aerotrace.search.base.Events;
import com.aerotrace.search.constant.SearchTopics;
import com.aerotrace.search.elastic.IndexAdmin;
import com.aerotrace.search.elastic.IndexFamily;
import com.aerotrace.search.elastic.SupportIndex;
import com.aerotrace.search.indexer.ReadModelWriter.CaseWrite;
import com.aerotrace.search.indexer.model.CaseChange;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * Completing what was indexed before its leg was known: only when a realtime lookup says something
 * waits for the leg, and then for every waiting case, not the first page only.
 */
@SpringBootTest
class LegCompletionTest extends AbstractSearchTest {

  @Autowired EventIndexer indexer;
  @Autowired ReadModelWriter writer;
  @Autowired ElasticsearchClient client;
  @MockitoSpyBean IndexAdmin admin;

  private void register(String bag, String leg, String origin) throws Exception {
    indexer.indexRegistrations(
        List.of(
            Events.record(
                SearchTopics.BAG_REGISTERED,
                bag,
                Events.registered(
                    bag, List.of(Events.leg(leg, "TK1981", origin, "LHR")), Instant.now()))));
  }

  private long withoutAirport(String index, String legId) throws Exception {
    client.indices().refresh(r -> r.index(index));
    return client
        .count(
            c ->
                c.index(index)
                    .query(
                        q ->
                            q.bool(
                                b ->
                                    b.filter(f -> f.term(t -> t.field("legId").value(legId)))
                                        .mustNot(m -> m.exists(e -> e.field("airport"))))))
        .count();
  }

  @Test
  @DisplayName("a registration nothing waits for costs no refresh, search or update by query")
  void nothingWaitingNoRefresh() throws Exception {
    clearInvocations(admin);

    register(newBagTag(), newId("leg"), "IST");

    verify(admin, never()).refresh(anyString());
  }

  @Test
  @DisplayName("more waiting cases than one completion page are all completed")
  void everyWaitingCaseIsCompleted() throws Exception {
    String leg = newId("leg");
    String bag = newBagTag();
    List<CaseWrite> cases = new ArrayList<>();
    for (int i = 0; i < 1_050; i++) {
      cases.add(
          new CaseWrite(
              new CaseChange(
                  newId("case"), bag, leg, RecoveryState.OPEN, 1, null, null, null, null, null),
              Instant.now()));
    }
    writer.writeCases(cases, admin.liveIndexes(IndexFamily.CASES));
    assertThat(withoutAirport(IndexFamily.CASES.readAlias(), leg)).isEqualTo(1_050);

    register(bag, leg, "ADB");

    assertThat(withoutAirport(IndexFamily.CASES.readAlias(), leg)).isZero();
  }

  @Test
  @DisplayName("a risk hour indexed before its leg gets the airport when the leg arrives")
  void waitingRiskHourIsCompleted() throws Exception {
    String leg = newId("leg");
    String bag = newBagTag();
    indexer.indexRisks(
        List.of(
            Events.record(
                Topics.RISK_CHANGED,
                bag,
                Events.risk(bag, leg, RiskLevel.HIGH, Instant.parse("2026-05-01T08:10:00Z")))));
    assertThat(withoutAirport(SupportIndex.RISK_HOURS.index(), leg)).isEqualTo(1);

    register(bag, leg, "ESB");

    assertThat(withoutAirport(SupportIndex.RISK_HOURS.index(), leg)).isZero();
    assertThat(client.exists(e -> e.index(SupportIndex.LEGS.index()).id("waiting:" + leg)).value())
        .isFalse();
  }
}
