package com.aerotrace.search.elastic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.transport.Version;
import com.aerotrace.search.base.AbstractSearchTest;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/** Templates, first indexes and aliases are created at start-up, idempotently and strictly. */
@SpringBootTest
class IndexBootstrapTest extends AbstractSearchTest {

  @Autowired ElasticsearchClient client;
  @Autowired IndexAdmin admin;

  @Test
  @DisplayName("the Testcontainers image is pinned to the client version")
  void imageMatchesTheClient() throws Exception {
    assertThat(ELASTICSEARCH_IMAGE).doesNotEndWith(":latest");
    Version client = Version.VERSION;
    assertThat(client.major() + "." + client.minor() + "." + client.maintenance())
        .isEqualTo(ELASTICSEARCH_VERSION);
    assertThat(this.client.info().version().number()).isEqualTo(ELASTICSEARCH_VERSION);
  }

  @Test
  @DisplayName("each read alias names exactly one index, which is also live")
  void aliasesAreInPlace() throws Exception {
    for (IndexFamily family : IndexFamily.values()) {
      String read = admin.readIndex(family);

      assertThat(read).startsWith(family.base() + "-v");
      assertThat(admin.liveIndexes(family)).contains(read);
      assertThat(client.indices().existsIndexTemplate(t -> t.name(family.base())).value()).isTrue();
    }
    for (SupportIndex index : SupportIndex.values()) {
      assertThat(client.indices().exists(e -> e.index(index.index())).value()).isTrue();
    }
  }

  @Test
  @DisplayName("starting again changes nothing")
  void bootstrapIsIdempotent() throws Exception {
    String before = admin.readIndex(IndexFamily.CASES);

    admin.bootstrap();

    assertThat(admin.readIndex(IndexFamily.CASES)).isEqualTo(before);
  }

  @Test
  @DisplayName("the mapping is strict: an unknown field is refused, so no stray data is indexed")
  void mappingIsStrict() {
    assertThatThrownBy(
            () ->
                client.index(
                    i ->
                        i.index(IndexFamily.CASES.readAlias())
                            .id(newId("strict"))
                            .document(Map.of("passengerRef", "P-1"))))
        .isInstanceOf(ElasticsearchException.class)
        .hasMessageContaining("strict_dynamic_mapping_exception");
  }

  @Test
  @DisplayName("index age is read from the name; the first index counts as old")
  void creationTimeFromTheName() {
    assertThat(IndexAdmin.createdAt("cases-v1")).isEqualTo(java.time.Instant.EPOCH);
    assertThat(IndexAdmin.createdAt("cases-v1700000000000").toEpochMilli())
        .isEqualTo(1_700_000_000_000L);
    assertThat(IndexAdmin.createdAt("cases-vX")).isEqualTo(java.time.Instant.EPOCH);
  }
}
