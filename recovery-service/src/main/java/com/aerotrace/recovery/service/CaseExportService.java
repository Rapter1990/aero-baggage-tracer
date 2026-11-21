package com.aerotrace.recovery.service;

import com.aerotrace.common.event.CaseChangedPayload;
import com.aerotrace.common.export.ExportPage;
import com.aerotrace.recovery.model.entity.RecoveryCaseEntity;
import com.aerotrace.recovery.repository.RecoveryCaseRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The internal case export the search-service rebuilds from (ADR 0004, decision 1). Keyset paging
 * by the case id (the primary key, which never changes): every case that exists for the whole run
 * is returned exactly once, whatever is inserted meanwhile, and a case inserted meanwhile at most
 * once. Read-only: it runs in a read-only transaction and has no path that writes.
 */
@Service
public class CaseExportService {

  private final RecoveryCaseRepository cases;

  public CaseExportService(RecoveryCaseRepository cases) {
    this.cases = cases;
  }

  /**
   * The cases after {@code after} (null for the first page), at most {@code size}, in id order.
   * {@code nextAfter} is the last id when more cases follow, otherwise null.
   */
  @Transactional(readOnly = true)
  public ExportPage<CaseChangedPayload> page(UUID after, int size) {
    // One more than asked, to know whether another page follows.
    Limit limit = Limit.of(size + 1);
    List<RecoveryCaseEntity> rows =
        after == null
            ? cases.findAllByOrderByIdAsc(limit)
            : cases.findByIdGreaterThanOrderByIdAsc(after, limit);
    boolean more = rows.size() > size;
    List<RecoveryCaseEntity> page = more ? rows.subList(0, size) : rows;
    String nextAfter = more ? page.getLast().getId().toString() : null;
    return new ExportPage<>(page.stream().map(RecoveryCaseService::payloadOf).toList(), nextAfter);
  }
}
