package com.aerotrace.search.base;

import com.aerotrace.search.indexer.model.CaseChange;
import com.aerotrace.search.reindex.SourceExportClient;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.IntConsumer;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * A stand-in for the export endpoints of #28 and #29, which do not exist yet: the owners' state in
 * memory, keyset-paged exactly as {@link SourceExportClient} specifies. It records every call, so a
 * test can show the rebuild only read, and it can block, fail or misbehave on a page.
 *
 * <p>What it cannot show: that the real journey- and recovery-services page and answer like this.
 * That is proven by #28 and #29 and, end to end, once they are merged.
 */
public final class InMemorySource implements SourceExportClient {

  /** Replaces the HTTP client in the tests that import it. */
  @TestConfiguration(proxyBeanMethods = false)
  public static class Config {
    @Bean
    @Primary
    InMemorySource inMemorySource() {
      return new InMemorySource();
    }
  }

  public final NavigableMap<String, CaseChange> cases = new ConcurrentSkipListMap<>();
  public final NavigableMap<String, BagExport> bags = new ConcurrentSkipListMap<>();
  public final List<String> calls = new CopyOnWriteArrayList<>();

  /** Called with the page number (1-based) before a case page is answered. */
  public volatile IntConsumer beforeCasePage = page -> {};

  /** When set, the first case page waits for it. */
  public volatile CountDownLatch gate;

  /** A case page with this number fails, as an unreachable owner would. */
  public volatile int failOnCasePage = -1;

  /** Each case page after the first repeats the previous page's last case (an owner bug). */
  public volatile boolean repeatAcrossPages;

  private int casePage;

  public void reset() {
    cases.clear();
    bags.clear();
    calls.clear();
    beforeCasePage = page -> {};
    gate = null;
    failOnCasePage = -1;
    repeatAcrossPages = false;
    casePage = 0;
  }

  @Override
  public synchronized ExportPage<CaseChange> cases(String after, int size) {
    calls.add("GET cases after=" + after + " size=" + size);
    casePage = after == null ? 1 : casePage + 1;
    CountDownLatch wait = gate;
    if (casePage == 1 && wait != null) {
      try {
        wait.await(60, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
    beforeCasePage.accept(casePage);
    if (casePage == failOnCasePage) {
      throw new IllegalStateException("source unreachable");
    }
    List<CaseChange> page = new ArrayList<>();
    if (repeatAcrossPages && after != null) {
      page.add(cases.get(after));
    }
    NavigableMap<String, CaseChange> rest = after == null ? cases : cases.tailMap(after, false);
    for (CaseChange c : rest.values()) {
      if (page.size() >= size) {
        break;
      }
      page.add(c);
    }
    String last = page.isEmpty() ? null : page.getLast().caseId();
    String next = last != null && !cases.tailMap(last, false).isEmpty() ? last : null;
    return new ExportPage<>(page, next);
  }

  @Override
  public ExportPage<BagExport> bags(String after, int size) {
    calls.add("GET bags after=" + after + " size=" + size);
    NavigableMap<String, BagExport> rest = after == null ? bags : bags.tailMap(after, false);
    List<BagExport> page = rest.values().stream().limit(size).toList();
    String next = rest.size() > size ? page.getLast().bagTag() : null;
    return new ExportPage<>(page, next);
  }

  /** An order-independent checksum of the source's rows: equal before and after a rebuild. */
  public long checksum() {
    long sum = 0;
    for (Map.Entry<String, CaseChange> e : cases.entrySet()) {
      sum += Objects.hash(e.getKey(), e.getValue());
    }
    for (Map.Entry<String, BagExport> e : bags.entrySet()) {
      sum += Objects.hash(e.getKey(), e.getValue());
    }
    return sum + 31L * cases.size() + bags.size();
  }
}
