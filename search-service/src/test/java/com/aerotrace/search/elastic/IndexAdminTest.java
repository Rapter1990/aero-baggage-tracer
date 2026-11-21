package com.aerotrace.search.elastic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.aerotrace.search.base.AbstractSearchTest;
import com.aerotrace.search.config.SearchProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * The alias operations of a rebuild on a real cluster. Uses the {@code journeys} family; the
 * rebuild tests leave the aliases as they found them (exactly one read index).
 */
@SpringBootTest
class IndexAdminTest extends AbstractSearchTest {

  @Autowired ElasticsearchClient client;
  @Autowired IndexAdmin admin;
  @Autowired SearchProperties properties;

  private boolean exists(String index) throws Exception {
    return client.indices().exists(e -> e.index(index)).value();
  }

  @Test
  @DisplayName(
      "a new index joins the live set, takes over the read alias atomically, and the old"
          + " one leaves both aliases and is deleted only after the grace period")
  void swapAndRetire() throws Exception {
    IndexFamily family = IndexFamily.JOURNEYS;
    String old = admin.readIndex(family);
    String next = admin.createNext(family);

    admin.addToLive(family, next);
    assertThat(admin.liveIndexes(family)).containsExactlyInAnyOrder(old, next);
    assertThat(admin.readIndex(family)).isEqualTo(old);

    admin.swapRead(family, next);
    assertThat(admin.indexesOf(family.readAlias())).containsExactly(next);
    assertThat(admin.liveIndexes(family)).containsExactly(next);

    // Within the grace period the old index stays, so an open cursor keeps working.
    admin.removeUnusedIndexes();
    assertThat(exists(old)).isTrue();

    // After it, it is gone.
    IndexAdmin later =
        new IndexAdmin(
            client,
            properties,
            Clock.offset(Clock.systemUTC(), properties.index().oldIndexGrace().plusMinutes(1)));
    later.removeUnusedIndexes();
    assertThat(exists(old)).isFalse();
    assertThat(exists(next)).isTrue();
  }

  @Test
  @DisplayName("a failed rebuild's index is discarded; the read index never is")
  void discard() throws Exception {
    IndexFamily family = IndexFamily.CASES;
    String next = admin.createNext(family);
    admin.addToLive(family, next);

    admin.discard(family, next);

    assertThat(exists(next)).isFalse();
    assertThat(admin.liveIndexes(family)).doesNotContain(next);
    assertThatThrownBy(() -> admin.discard(family, admin.readIndex(family)))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  @DisplayName(
      "an index in no alias that was never read is deleted once it is older than the"
          + " grace period, and only then")
  void orphanIsDeletedByAge() throws Exception {
    Instant now = Instant.now();
    IndexAdmin creator = new IndexAdmin(client, properties, Clock.fixed(now, ZoneOffset.UTC));
    String orphan = creator.createNext(IndexFamily.CASES);

    admin.removeUnusedIndexes();
    assertThat(exists(orphan)).isTrue();

    new IndexAdmin(
            client,
            properties,
            Clock.fixed(
                now.plus(properties.index().oldIndexGrace()).plus(Duration.ofSeconds(1)),
                ZoneOffset.UTC))
        .removeUnusedIndexes();
    assertThat(exists(orphan)).isFalse();
  }

  @Test
  @DisplayName("an unknown alias has no indexes")
  void unknownAlias() throws Exception {
    assertThat(admin.indexesOf("no-such-alias")).isEmpty();
  }
}
