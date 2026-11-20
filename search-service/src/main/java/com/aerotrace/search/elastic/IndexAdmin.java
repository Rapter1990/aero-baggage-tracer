package com.aerotrace.search.elastic;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch.indices.update_aliases.Action;
import com.aerotrace.search.config.SearchProperties;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;
import jakarta.json.JsonReader;
import jakarta.json.JsonValue;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Creates the index templates, the first indexes and their aliases, resolves which indexes are read
 * and written, swaps the read alias after a rebuild and removes indexes nobody uses any more.
 *
 * <p>Runs before the Kafka listeners start (it initializes with its bean), so no event can be
 * written through an alias that does not exist yet: Elasticsearch would create a plain index of
 * that name instead.
 */
@Component
public class IndexAdmin implements InitializingBean {

  private static final Logger LOG = LoggerFactory.getLogger(IndexAdmin.class);
  private static final String REPLICAS = "\"__REPLICAS__\"";
  private static final int STARTUP_ATTEMPTS = 30;

  private final ElasticsearchClient client;
  private final SearchProperties.Index settings;
  private final Clock clock;

  public IndexAdmin(ElasticsearchClient client, SearchProperties properties, Clock clock) {
    this.client = client;
    this.settings = properties.index();
    this.clock = clock;
  }

  @Override
  public void afterPropertiesSet() throws InterruptedException {
    for (int attempt = 1; ; attempt++) {
      try {
        bootstrap();
        return;
      } catch (IOException | RuntimeException e) {
        if (attempt >= STARTUP_ATTEMPTS) {
          throw new IllegalStateException("Elasticsearch could not be prepared", e);
        }
        LOG.warn("Elasticsearch not ready (attempt {}): {}", attempt, e.getClass().getSimpleName());
        Thread.sleep(Math.min(1_000L * attempt, 5_000L));
      }
    }
  }

  /** Templates, first indexes and aliases. Idempotent: safe on every start of every instance. */
  void bootstrap() throws IOException {
    for (IndexFamily family : IndexFamily.values()) {
      String body =
          "{\"index_patterns\":[\""
              + family.pattern()
              + "\"],\"priority\":100,\"template\":"
              + resource(family.templateResource())
              + "}";
      client
          .indices()
          .putIndexTemplate(t -> t.name(family.base()).withJson(new StringReader(body)));
      if (!client.indices().existsAlias(a -> a.name(family.readAlias())).value()) {
        createIgnoringExisting(
            family.initialIndex(), family.readAlias(), family.liveAlias(), null /* template */);
      }
    }
    for (SupportIndex index : SupportIndex.values()) {
      if (!client.indices().exists(e -> e.index(index.index())).value()) {
        createIgnoringExisting(index.index(), null, null, resource(index.resource()));
      } else {
        addNewFields(index);
      }
    }
  }

  /**
   * Adds the fields a newer template has to an index an older start created (a mapping update that
   * only adds fields is allowed and idempotent), so a field added to the template, such as the
   * audit fields of the jobs, is not refused by the strict mapping of the index that already
   * exists. Only the properties the index lacks are sent, so a later template change to an existing
   * field cannot make the update fail; and a refusal is logged, never thrown, because it must not
   * stop the service from starting (a write that needs the field then fails visibly). The read
   * models need no such step: a rebuild creates its index from the template.
   */
  private void addNewFields(SupportIndex index) {
    try {
      JsonObject wanted;
      try (JsonReader reader = Json.createReader(new StringReader(resource(index.resource())))) {
        wanted = reader.readObject().getJsonObject("mappings");
      }
      Set<String> present =
          client
              .indices()
              .getMapping(m -> m.index(index.index()))
              .get(index.index())
              .mappings()
              .properties()
              .keySet();
      JsonObjectBuilder missing = Json.createObjectBuilder();
      for (Map.Entry<String, JsonValue> property : wanted.getJsonObject("properties").entrySet()) {
        if (!present.contains(property.getKey())) {
          missing.add(property.getKey(), property.getValue());
        }
      }
      JsonObject add = missing.build();
      if (add.isEmpty()) {
        return;
      }
      String body = Json.createObjectBuilder().add("properties", add).build().toString();
      client.indices().putMapping(m -> m.index(index.index()).withJson(new StringReader(body)));
      LOG.info("Added {} to the mapping of {}", add.keySet(), index.index());
    } catch (IOException | RuntimeException e) {
      LOG.warn(
          "Mapping of {} not brought up to date: {}", index.index(), e.getClass().getSimpleName());
    }
  }

  private void createIgnoringExisting(String index, String read, String live, String body)
      throws IOException {
    try {
      client
          .indices()
          .create(
              c -> {
                c.index(index);
                if (body != null) {
                  c.withJson(new StringReader(body));
                }
                if (read != null) {
                  c.aliases(read, a -> a).aliases(live, a -> a);
                }
                return c;
              });
      LOG.info("Created index {}", index);
    } catch (ElasticsearchException e) {
      if (!"resource_already_exists_exception".equals(e.error().type())) {
        throw e;
      }
    }
  }

  private String resource(String path) {
    try (InputStream in = new ClassPathResource(path).getInputStream()) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8)
          .replace(REPLICAS, String.valueOf(settings.replicas()));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** The indexes the indexer writes to: one, or two while a rebuild runs. */
  public List<String> liveIndexes(IndexFamily family) throws IOException {
    return indexesOf(family.liveAlias());
  }

  /** The index searches read (exactly one). */
  public String readIndex(IndexFamily family) throws IOException {
    List<String> indexes = indexesOf(family.readAlias());
    if (indexes.size() != 1) {
      throw new IllegalStateException(family.readAlias() + " must name exactly one index");
    }
    return indexes.get(0);
  }

  public List<String> indexesOf(String alias) throws IOException {
    if (!client.indices().existsAlias(a -> a.name(alias)).value()) {
      return List.of();
    }
    return new ArrayList<>(
        new TreeSet<>(client.indices().getAlias(a -> a.name(alias)).aliases().keySet()));
  }

  /** Creates the next index of a family from its template; it is in no alias yet. */
  public String createNext(IndexFamily family) throws IOException {
    String index = family.base() + "-v" + clock.millis();
    client.indices().create(c -> c.index(index));
    return index;
  }

  /** Live events now also reach the new index. */
  public void addToLive(IndexFamily family, String index) throws IOException {
    client
        .indices()
        .updateAliases(
            u -> u.actions(Action.of(a -> a.add(x -> x.index(index).alias(family.liveAlias())))));
  }

  /**
   * One atomic {@code _aliases} call for every family of a rebuild: each read alias moves to its
   * new index, and the old indexes leave both aliases. Searches see either the old indexes or the
   * new ones, never none, both, or one family moved without the other: if Elasticsearch refuses any
   * action, no alias moves.
   *
   * @param next the new index of each rebuilt family
   */
  public void swapRead(Map<IndexFamily, String> next) throws IOException {
    List<Action> actions = new ArrayList<>();
    Set<String> retired = new TreeSet<>();
    for (Map.Entry<IndexFamily, String> entry : next.entrySet()) {
      IndexFamily family = entry.getKey();
      String newIndex = entry.getValue();
      List<String> oldRead = indexesOf(family.readAlias());
      List<String> oldLive = indexesOf(family.liveAlias());
      for (String old : oldRead) {
        if (!old.equals(newIndex)) {
          actions.add(Action.of(a -> a.remove(r -> r.index(old).alias(family.readAlias()))));
        }
      }
      actions.add(Action.of(a -> a.add(x -> x.index(newIndex).alias(family.readAlias()))));
      for (String old : oldLive) {
        if (!old.equals(newIndex)) {
          actions.add(Action.of(a -> a.remove(r -> r.index(old).alias(family.liveAlias()))));
        }
      }
      retired.addAll(oldRead);
      retired.addAll(oldLive);
      retired.remove(newIndex);
    }
    if (actions.isEmpty()) {
      return;
    }
    client.indices().updateAliases(u -> u.actions(actions));
    // The grace period of an old index starts now, when it stops being read.
    for (String old : retired) {
      client.index(
          i ->
              i.index(SupportIndex.JOBS.index())
                  .id(RETIRED_PREFIX + old)
                  .document(
                      Map.of(
                          "kind",
                          "RETIRED",
                          "jobId",
                          old,
                          "finishedAt",
                          Elastic.iso(clock.instant()))));
    }
  }

  /** {@link #swapRead(Map)} for one family. */
  public void swapRead(IndexFamily family, String newIndex) throws IOException {
    swapRead(Map.of(family, newIndex));
  }

  /**
   * Discards every index of the family that is written but not read: what a rebuild whose instance
   * died left behind (it never swapped, and nobody would ever take its index out of the live
   * alias). Only called by the job that took over the abandoned job's lock.
   *
   * @return the discarded indexes
   */
  public List<String> discardUnread(IndexFamily family) throws IOException {
    List<String> read = indexesOf(family.readAlias());
    List<String> discarded = new ArrayList<>();
    for (String index : indexesOf(family.liveAlias())) {
      if (!read.contains(index)) {
        discard(family, index);
        discarded.add(index);
      }
    }
    return discarded;
  }

  private static final String RETIRED_PREFIX = "retired:";

  /** Takes a failed rebuild's index out of the live alias and deletes it. */
  public void discard(IndexFamily family, String index) throws IOException {
    if (indexesOf(family.readAlias()).contains(index)) {
      throw new IllegalStateException("the read index is never discarded");
    }
    if (indexesOf(family.liveAlias()).contains(index)) {
      client
          .indices()
          .updateAliases(
              u ->
                  u.actions(
                      Action.of(a -> a.remove(r -> r.index(index).alias(family.liveAlias())))));
    }
    client.indices().delete(d -> d.index(index).ignoreUnavailable(true));
  }

  public void refresh(String index) throws IOException {
    client.indices().refresh(r -> r.index(index));
  }

  /**
   * Deletes the indexes of a family that are in neither alias once the grace period (longer than
   * the point-in-time keep-alive) has passed: for an index retired by a swap, counted from the
   * swap; for any other index, from its creation, which its name carries. So a rebuild's index that
   * was just created and is not in an alias yet is left alone, and an open cursor never loses its
   * index.
   */
  @Scheduled(
      fixedDelayString = "${aerotrace.search.index.janitor-delay:1m}",
      initialDelayString = "${aerotrace.search.index.janitor-delay:1m}")
  public void removeUnusedIndexes() {
    try {
      for (IndexFamily family : IndexFamily.values()) {
        Set<String> used = new TreeSet<>(indexesOf(family.readAlias()));
        used.addAll(indexesOf(family.liveAlias()));
        Instant cutOff = clock.instant().minus(settings.oldIndexGrace());
        for (String index :
            client.indices().get(g -> g.index(family.pattern())).indices().keySet()) {
          if (family.owns(index) && !used.contains(index) && unusedSince(index).isBefore(cutOff)) {
            client.indices().delete(d -> d.index(index).ignoreUnavailable(true));
            client.delete(d -> d.index(SupportIndex.JOBS.index()).id(RETIRED_PREFIX + index));
            LOG.info("Deleted unused index {}", index);
          }
        }
      }
    } catch (IOException | RuntimeException e) {
      LOG.warn("Unused indexes not checked: {}", e.getClass().getSimpleName());
    }
  }

  @SuppressWarnings("rawtypes")
  private Instant unusedSince(String index) throws IOException {
    var retired =
        client.get(g -> g.index(SupportIndex.JOBS.index()).id(RETIRED_PREFIX + index), Map.class);
    if (retired.found() && retired.source() != null) {
      return Elastic.instant(retired.source().get("finishedAt"));
    }
    return createdAt(index);
  }

  /** {@code cases-v1} counts as old; later indexes carry their creation time in the name. */
  static Instant createdAt(String index) {
    String suffix = index.substring(index.lastIndexOf("-v") + 2);
    try {
      long millis = Long.parseLong(suffix);
      return millis < 1_000_000 ? Instant.EPOCH : Instant.ofEpochMilli(millis);
    } catch (NumberFormatException e) {
      return Instant.EPOCH;
    }
  }
}
