package com.aerotrace.search.indexer;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.bulk.BulkOperation;
import co.elastic.clients.elasticsearch.core.bulk.BulkResponseItem;
import com.aerotrace.search.elastic.Elastic;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Sends one bulk request and checks every item: Elasticsearch answers a bulk with 200 even when
 * items failed. Items refused with a status that may pass (429, 5xx) are sent again a few times; if
 * they still fail, {@link TransientIndexingException}. An unreachable cluster is an {@link
 * IOException} for the caller.
 */
@Component
public class BulkExecutor {

  /** One operation of a bulk, and the input it belongs to. */
  public record Op(int item, BulkOperation operation) {}

  /** Elasticsearch's answer to one operation. */
  public record Ack(
      int item, String index, int status, String result, String errorType, String errorReason) {

    public boolean ok() {
      return status >= 200 && status < 300;
    }

    public boolean conflict() {
      return status == 409;
    }
  }

  private static final int ATTEMPTS = 4;
  private static final long FIRST_WAIT_MILLIS = 100;

  private final ElasticsearchClient client;

  public BulkExecutor(ElasticsearchClient client) {
    this.client = client;
  }

  public List<Ack> execute(List<Op> ops) throws IOException {
    List<Ack> acks = new ArrayList<>();
    List<Op> pending = ops;
    for (int attempt = 1; !pending.isEmpty(); attempt++) {
      List<Op> current = pending;
      BulkResponse response =
          client.bulk(b -> b.operations(current.stream().map(Op::operation).toList()));
      List<Op> retry = new ArrayList<>();
      List<BulkResponseItem> items = response.items();
      for (int i = 0; i < items.size(); i++) {
        BulkResponseItem item = items.get(i);
        if (Elastic.isTransient(item.status())) {
          retry.add(current.get(i));
        } else {
          acks.add(
              new Ack(
                  current.get(i).item(),
                  item.index(),
                  item.status(),
                  item.result(),
                  item.error() == null ? null : item.error().type(),
                  item.error() == null ? null : item.error().reason()));
        }
      }
      if (!retry.isEmpty() && attempt >= ATTEMPTS) {
        throw new TransientIndexingException(
            retry.size() + " bulk items still refused after " + attempt + " attempts");
      }
      if (!retry.isEmpty()) {
        sleep(FIRST_WAIT_MILLIS << (attempt - 1));
      }
      pending = retry;
    }
    return acks;
  }

  private static void sleep(long millis) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new TransientIndexingException("interrupted");
    }
  }
}
