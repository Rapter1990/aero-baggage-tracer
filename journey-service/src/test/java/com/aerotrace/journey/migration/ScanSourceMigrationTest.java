package com.aerotrace.journey.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aerotrace.journey.base.AbstractTestContainerConfiguration;
import com.aerotrace.journey.config.ContractStepMonitor;
import com.aerotrace.journey.constant.MetricNames;
import com.aerotrace.journey.constant.ScanSources;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.persistence.EntityManager;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import liquibase.Liquibase;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * AT-17: apply the {@code scan_source} migrations (V2 adds the nullable column and backfills, V3
 * enforces NOT NULL) to a database that already holds data written by the first four changesets
 * (V1), and show that no row is lost and no old value changes.
 *
 * <p>The data is written and read with JPA native queries ({@code
 * EntityManager.createNativeQuery}), each in its own transaction. Liquibase is switched off at
 * start-up and driven by the test, so it can stop after V1. The test has a database of its own
 * inside the shared container (the SQL of the changelog names the {@code journey} schema, so
 * another test class's schema cannot be reused), and every test starts from an empty {@code
 * journey} schema. The first four changesets are the V1 baseline; the fifth is V2; the sixth is V3;
 * 007 and 008 add and validate the CHECK on the known sources; 009 is sample data (context dev).
 */
@SpringBootTest(
    properties = {"spring.liquibase.enabled=false", "spring.jpa.hibernate.ddl-auto=none"})
class ScanSourceMigrationTest extends AbstractTestContainerConfiguration {

  private static final String CHANGELOG = "db/changelog/db.changelog-master.yaml";
  private static final int V1_CHANGESETS = 4;
  private static final int V2_CHANGESETS = 5;

  /** The changesets that run by default (001 to 008); 009 is sample data for the "dev" context. */
  private static final int ALL_CHANGESETS = 8;

  /** The default of the service: the additive steps and the steps that make a change mandatory. */
  private static final String DEFAULT_CONTEXTS = "expand,contract";

  private static final int BAGS = 200;
  private static final int SCANS_PER_BAG = 3;

  /** The tables V2 and V3 must leave untouched, compared as a count and a checksum each. */
  private static final String[] TABLES = {
    "baggage", "itinerary", "itinerary_leg", "scan_audit", "outbox_event", "processed_event"
  };

  @Autowired private EntityManager entityManager;
  @Autowired private PlatformTransactionManager transactionManager;
  @Autowired private DataSource dataSource;
  @Autowired private ContractStepMonitor contractStep;
  @Autowired private MeterRegistry meters;

  /**
   * A database of this class's own in the shared container. It is a {@code DataSource} bean, not a
   * property override: a property of the same name from the base class would win over a second
   * {@code @DynamicPropertySource}, and the test would then drop the shared schema.
   */
  @TestConfiguration(proxyBeanMethods = false)
  static class OwnDatabase {
    @Bean
    DataSource dataSource() {
      String name = "migration_" + UUID.randomUUID().toString().replace("-", "");
      String base =
          "jdbc:postgresql://%s:%d/"
              .formatted(postgres().getHost(), postgres().getFirstMappedPort());
      try (Connection admin =
              DriverManager.getConnection(
                  base + postgres().getDatabaseName(),
                  postgres().getUsername(),
                  postgres().getPassword());
          Statement create = admin.createStatement()) {
        create.execute("CREATE DATABASE " + name);
      } catch (SQLException e) {
        throw new IllegalStateException("Cannot create the migration test database", e);
      }
      return DataSourceBuilder.create()
          .url(base + name)
          .username(postgres().getUsername())
          .password(postgres().getPassword())
          .build();
    }
  }

  @BeforeEach
  void emptySchema() {
    // Never drop a schema of the shared database.
    String database =
        new TransactionTemplate(transactionManager)
            .execute(
                status ->
                    (String)
                        entityManager
                            .createNativeQuery("SELECT current_database()")
                            .getSingleResult());
    assertThat(database).startsWith("migration_");
    execute("DROP SCHEMA IF EXISTS journey CASCADE");
    execute("CREATE SCHEMA journey");
  }

  @Test
  @DisplayName("AT-17 V2 and V3 applied to preloaded V1 data lose no row and change no value")
  void migrationKeepsEveryRow() throws Exception {
    migrate(V1_CHANGESETS);
    preload();
    Map<String, Snapshot> before = snapshot();
    assertThat(before.get("scan_audit").rows()).isEqualTo(BAGS * SCANS_PER_BAG);

    migrate(Integer.MAX_VALUE);

    assertThat(snapshot()).isEqualTo(before);
    assertThat(count("SELECT count(*) FROM journey.scan_audit WHERE scan_source = 'API'"))
        .isEqualTo(BAGS * SCANS_PER_BAG);
  }

  @Test
  @DisplayName("AT-17 V3 makes scan_source mandatory")
  void scanSourceIsNotNullAfterV3() throws Exception {
    migrate(V1_CHANGESETS);
    preload();
    migrate(Integer.MAX_VALUE);

    assertThat(count(scanSourceColumn("NO"))).isEqualTo(1);
    assertThatThrownBy(() -> insertScan("NULL", "0000000001", "evt-null-source"))
        .hasStackTraceContaining("scan_source");
  }

  @Test
  @DisplayName("AT-17 the append-only protection of scan_audit survives the backfill")
  void scanAuditStaysAppendOnly() throws Exception {
    migrate(V1_CHANGESETS);
    preload();
    migrate(Integer.MAX_VALUE);

    assertThatThrownBy(() -> execute("UPDATE journey.scan_audit SET checkpoint = 'X'"))
        .hasStackTraceContaining("append-only");
    assertThatThrownBy(() -> execute("DELETE FROM journey.scan_audit"))
        .hasStackTraceContaining("append-only");
    assertThatThrownBy(() -> execute("TRUNCATE journey.scan_audit"))
        .hasStackTraceContaining("append-only");
  }

  @Test
  @DisplayName("AT-17 running the changelog again changes nothing")
  void rerunIsSafe() throws Exception {
    migrate(V1_CHANGESETS);
    preload();
    migrate(Integer.MAX_VALUE);
    Map<String, Snapshot> afterFirstRun = snapshot();

    migrate(Integer.MAX_VALUE);

    assertThat(snapshot()).isEqualTo(afterFirstRun);
    assertThat(count("SELECT count(*) FROM journey.databasechangelog")).isEqualTo(ALL_CHANGESETS);
  }

  @Test
  @DisplayName("AT-17 an old application still inserts scans between V2 and V3")
  void oldWriterIsAcceptedBetweenV2AndV3() throws Exception {
    migrate(V2_CHANGESETS);

    // An instance of the previous version does not know the column and leaves it out.
    insertBag("9990000001");
    execute(
        "INSERT INTO journey.scan_audit (id, event_id, bag_tag, protocol, airport, checkpoint, "
            + "device_sequence, occurred_at, device_timestamp, source_timezone, ingested_at) "
            + "VALUES (gen_random_uuid(), 'evt-old-writer', '9990000001', 'BARCODE', 'IST', "
            + "'CHECK_IN', 1, now(), now(), 'UTC', now())");

    assertThat(count("SELECT count(*) FROM journey.scan_audit WHERE scan_source IS NULL")).isZero();
    migrate(Integer.MAX_VALUE);
    assertThat(count("SELECT count(*) FROM journey.scan_audit WHERE scan_source = 'API'"))
        .isEqualTo(1);
  }

  @Test
  @DisplayName("AT-17 V3 halts and changes nothing while a row still has no scan_source")
  void v3RefusesWhileRowsAreUnvalidated() throws Exception {
    migrate(V2_CHANGESETS);
    insertBag("9990000002");
    insertScan("NULL", "9990000002", "evt-unvalidated");
    Map<String, Snapshot> before = snapshot();

    assertThatThrownBy(() -> migrate(Integer.MAX_VALUE)).hasStackTraceContaining("Precondition");

    assertThat(snapshot()).isEqualTo(before);
    assertThat(count(scanSourceColumn("YES"))).isEqualTo(1);
    assertThat(
            count(
                "SELECT count(*) FROM journey.databasechangelog "
                    + "WHERE id = '006-scan-source-not-null'"))
        .isZero();
  }

  @Test
  @DisplayName(
      "AT-17 the documented rollback (check, V3, V2) removes the column and keeps every row")
  void rollbackRestoresV1AndMigrationCanBeRepeated() throws Exception {
    migrate(V1_CHANGESETS);
    preload();
    Map<String, Snapshot> before = snapshot();
    migrate(Integer.MAX_VALUE);

    rollback(1); // the validation of the CHECK: nothing to undo, the constraint stays
    assertThat(count(constraint("ck_scan_audit_scan_source"))).isEqualTo(1);

    rollback(1); // the CHECK constraint
    assertThat(count(constraint("ck_scan_audit_scan_source"))).isZero();
    assertThat(count(scanSourceColumn("NO"))).isEqualTo(1);

    rollback(1); // V3
    assertThat(count(scanSourceColumn("YES"))).isEqualTo(1);
    assertThat(snapshot()).isEqualTo(before);

    rollback(1); // V2
    assertThat(count(scanSourceColumn("YES")) + count(scanSourceColumn("NO"))).isZero();
    assertThat(snapshot()).isEqualTo(before);
    assertThat(count("SELECT count(*) FROM journey.databasechangelog")).isEqualTo(V1_CHANGESETS);

    migrate(Integer.MAX_VALUE);
    assertThat(snapshot()).isEqualTo(before);
    assertThat(count(scanSourceColumn("NO"))).isEqualTo(1);
    assertThat(count("SELECT count(*) FROM journey.scan_audit WHERE scan_source = 'API'"))
        .isEqualTo(BAGS * SCANS_PER_BAG);
  }

  @Test
  @DisplayName("AT-17 V3 can wait one release: the expand context leaves the column nullable")
  void contractStepWaitsForItsContext() throws Exception {
    migrate(V1_CHANGESETS);
    preload();
    Map<String, Snapshot> before = snapshot();

    migrate(Integer.MAX_VALUE, "expand");

    assertThat(count(scanSourceColumn("YES"))).isEqualTo(1);
    assertThat(changeset("006-scan-source-not-null")).isZero();
    assertThat(changeset("005-scan-source")).isEqualTo(1);
    assertThat(snapshot()).isEqualTo(before);
    // A rollout that stays on "expand" is visible: the monitor and its gauge say the step is due.
    assertThat(contractStep.pending()).isTrue();
    assertThat(meters.get(MetricNames.MIGRATION_CONTRACT_PENDING).gauge().value()).isEqualTo(1.0);

    migrate(Integer.MAX_VALUE, DEFAULT_CONTEXTS);

    assertThat(contractStep.pending()).isFalse();
    assertThat(meters.get(MetricNames.MIGRATION_CONTRACT_PENDING).gauge().value()).isEqualTo(0.0);
    assertThat(count(scanSourceColumn("NO"))).isEqualTo(1);
    assertThat(changeset("006-scan-source-not-null")).isEqualTo(1);
    assertThat(snapshot()).isEqualTo(before);
  }

  @Test
  @DisplayName("AT-17 only a known scan_source is accepted")
  void unknownScanSourceIsRefused() throws Exception {
    migrate(V1_CHANGESETS);
    preload();
    migrate(Integer.MAX_VALUE);

    assertThat(count(constraint("ck_scan_audit_scan_source") + " AND convalidated")).isEqualTo(1);
    // The value the code writes is one the constraint lists.
    insertScan("'" + ScanSources.API + "'", "0000000001", "evt-known-source");
    assertThatThrownBy(() -> insertScan("'BATCH'", "0000000001", "evt-unknown-source"))
        .hasStackTraceContaining("ck_scan_audit_scan_source");
  }

  @Test
  @DisplayName("AT-17 the sample journeys exist only in the dev context and can be rolled back")
  void sampleJourneysAreOptIn() throws Exception {
    migrate(Integer.MAX_VALUE);
    assertThat(count("SELECT count(*) FROM journey.baggage")).isZero();

    migrate(Integer.MAX_VALUE, DEFAULT_CONTEXTS + ",dev");

    assertThat(count("SELECT count(*) FROM journey.baggage WHERE bag_tag LIKE '90000000__'"))
        .isEqualTo(20);
    assertThat(count("SELECT count(*) FROM journey.itinerary_leg")).isEqualTo(40);
    assertThat(count("SELECT count(*) FROM journey.scan_audit WHERE scan_source = 'API'"))
        .isEqualTo(60);
    // The state the application keeps on a bag is that of its newest scan.
    assertThat(
            count(
                "SELECT count(*) FROM journey.baggage WHERE latest_checkpoint = 'LOADED' "
                    + "AND latest_scan_id IS NOT NULL AND latest_device_sequence = 3"))
        .isEqualTo(20);

    rollback(1);

    assertThat(count("SELECT count(*) FROM journey.baggage")).isZero();
    assertThat(count("SELECT count(*) FROM journey.scan_audit")).isZero();
    assertThat(
            count(
                "SELECT count(*) FROM pg_trigger WHERE tgname = 'trg_scan_audit_no_update_delete' "
                    + "AND tgenabled = 'O'"))
        .isEqualTo(1);
  }

  @Test
  @DisplayName("AT-17 a backfill that fails leaves no column and the append-only trigger enabled")
  void failedBackfillIsRolledBackAsAWhole() throws Exception {
    migrate(V1_CHANGESETS);
    preload();
    // A second UPDATE trigger that always refuses, so the backfill fails after the append-only
    // trigger was switched off (the changeset disables it first).
    execute(
        "CREATE TRIGGER trg_test_fail_backfill BEFORE UPDATE ON journey.scan_audit "
            + "FOR EACH ROW EXECUTE FUNCTION journey.reject_scan_audit_change()");
    Map<String, Snapshot> before = snapshot();

    assertThatThrownBy(() -> migrate(V2_CHANGESETS)).hasStackTraceContaining("append-only");

    assertThat(
            count(
                "SELECT count(*) FROM pg_trigger WHERE tgname = 'trg_scan_audit_no_update_delete' "
                    + "AND tgenabled = 'O'"))
        .isEqualTo(1);
    assertThat(count(scanSourceColumn("YES")) + count(scanSourceColumn("NO"))).isZero();
    assertThat(
            count(
                "SELECT count(*) FROM journey.databasechangelog " + "WHERE id = '005-scan-source'"))
        .isZero();
    assertThat(snapshot()).isEqualTo(before);
    assertThatThrownBy(() -> execute("UPDATE journey.scan_audit SET checkpoint = 'X'"))
        .hasStackTraceContaining("append-only");
  }

  @Test
  @DisplayName("AT-17 the sample journeys are skipped when the database already holds bags")
  void sampleJourneysNeverMixWithRealData() throws Exception {
    migrate(Integer.MAX_VALUE);
    insertBag("9000000007");

    migrate(Integer.MAX_VALUE, DEFAULT_CONTEXTS + ",dev");

    assertThat(count("SELECT count(*) FROM journey.baggage")).isEqualTo(1);
    assertThat(count("SELECT count(*) FROM journey.itinerary")).isZero();
    assertThat(changeset("009-dev-sample-journeys")).isEqualTo(1);
  }

  @Test
  @DisplayName("AT-17 a database that ran the first version of 006 still validates")
  void firstVersionOfTheContractStepStillValidates() throws Exception {
    migrate(V2_CHANGESETS);
    // What a database looks like that applied 006 when this file had the checksum below (the
    // version first pushed on this branch): the NOT NULL is in place and the changelog row exists.
    execute("ALTER TABLE journey.scan_audit ALTER COLUMN scan_source SET NOT NULL");
    execute(
        "INSERT INTO journey.databasechangelog (id, author, filename, dateexecuted, "
            + "orderexecuted, exectype, md5sum, description, liquibase, contexts) VALUES ("
            + "'006-scan-source-not-null', 'journey', "
            + "'db/changelog/changes/006-scan-source-not-null.sql', now(), 6, 'EXECUTED', "
            + "'9:c9a8f5041f3d892ced8e8065fa06f950', 'sql', '5.0.0', 'contract')");

    migrate(Integer.MAX_VALUE);

    assertThat(count("SELECT count(*) FROM journey.databasechangelog")).isEqualTo(ALL_CHANGESETS);
    assertThat(changeset("007-scan-source-check")).isEqualTo(1);
    assertThat(count(scanSourceColumn("NO"))).isEqualTo(1);
  }

  // ---------------------------------------------------------------------------------------------

  /** Applies at most {@code changesets} not yet applied changesets, in the default contexts. */
  private void migrate(int changesets) throws Exception {
    migrate(changesets, DEFAULT_CONTEXTS);
  }

  /** Applies at most {@code changesets} not yet applied changesets of the real changelog. */
  private void migrate(int changesets, String contexts) throws Exception {
    try (Connection connection = dataSource.getConnection()) {
      Database database =
          DatabaseFactory.getInstance()
              .findCorrectDatabaseImplementation(new JdbcConnection(connection));
      database.setDefaultSchemaName("journey");
      database.setLiquibaseSchemaName("journey");
      Liquibase liquibase = new Liquibase(CHANGELOG, new ClassLoaderResourceAccessor(), database);
      if (changesets == Integer.MAX_VALUE) {
        liquibase.update(contexts);
      } else {
        liquibase.update(changesets, contexts);
      }
    }
  }

  /** Rolls back the last {@code changesets} applied changesets, as the rollback recipe does. */
  private void rollback(int changesets) throws Exception {
    try (Connection connection = dataSource.getConnection()) {
      Database database =
          DatabaseFactory.getInstance()
              .findCorrectDatabaseImplementation(new JdbcConnection(connection));
      database.setDefaultSchemaName("journey");
      database.setLiquibaseSchemaName("journey");
      new Liquibase(CHANGELOG, new ClassLoaderResourceAccessor(), database)
          .rollback(changesets, "");
    }
  }

  /** One native statement in a transaction of its own, so a refused statement leaves no trace. */
  private void execute(String sql) {
    new TransactionTemplate(transactionManager)
        .executeWithoutResult(status -> entityManager.createNativeQuery(sql).executeUpdate());
  }

  private long count(String sql) {
    return new TransactionTemplate(transactionManager)
        .execute(
            status ->
                ((Number) entityManager.createNativeQuery(sql).getSingleResult()).longValue());
  }

  /** Legacy data as the V1 application wrote it: bags, itineraries, legs and scans. */
  private void preload() {
    execute(
        "INSERT INTO journey.baggage (id, bag_tag, passenger_ref) "
            + "SELECT gen_random_uuid(), lpad(n::text, 10, '0'), 'P-' || n "
            + "FROM generate_series(1, "
            + BAGS
            + ") n");
    execute(
        "INSERT INTO journey.itinerary (id, baggage_id) "
            + "SELECT gen_random_uuid(), id FROM journey.baggage");
    execute(
        "INSERT INTO journey.itinerary_leg (id, itinerary_id, leg_no, flight_number, origin, "
            + "destination, departure_at, arrival_at) "
            + "SELECT gen_random_uuid(), i.id, leg, 'AT' || leg, 'IST', 'FRA', "
            + "now() + make_interval(hours => leg), now() + make_interval(hours => leg + 3) "
            + "FROM journey.itinerary i, generate_series(1, 2) leg");
    execute(
        "INSERT INTO journey.scan_audit (id, event_id, bag_tag, protocol, airport, checkpoint, "
            + "device_sequence, occurred_at, device_timestamp, source_timezone, ingested_at, "
            + "late_event, applied) "
            + "SELECT gen_random_uuid(), 'evt-' || b.bag_tag || '-' || s, b.bag_tag, "
            + "(ARRAY['BARCODE','RFID','CONVEYOR'])[1 + s % 3], 'IST', 'CHECK_IN', s, "
            + "now() - make_interval(mins => s), now() - make_interval(mins => s), "
            + "'Europe/Istanbul', now(), s = 3, s <> 3 "
            + "FROM journey.baggage b, generate_series(1, "
            + SCANS_PER_BAG
            + ") s");
    execute(
        "INSERT INTO journey.outbox_event (id, topic, message_key, payload) "
            + "SELECT gen_random_uuid(), 'baggage.scan.v1', bag_tag, '{}' FROM journey.baggage");
    execute(
        "INSERT INTO journey.processed_event (event_id) "
            + "SELECT 'evt-' || bag_tag || '-1' FROM journey.baggage");
  }

  private void insertBag(String bagTag) {
    execute(
        "INSERT INTO journey.baggage (id, bag_tag, passenger_ref) "
            + "VALUES (gen_random_uuid(), '"
            + bagTag
            + "', 'P-1')");
  }

  /** {@code source} is a SQL literal, so a test can pass {@code NULL}. */
  private void insertScan(String source, String bagTag, String eventId) {
    execute(
        "INSERT INTO journey.scan_audit (id, event_id, bag_tag, protocol, airport, checkpoint, "
            + "device_sequence, occurred_at, device_timestamp, source_timezone, ingested_at, "
            + "scan_source) VALUES (gen_random_uuid(), '"
            + eventId
            + "', '"
            + bagTag
            + "', 'BARCODE', 'IST', 'CHECK_IN', 1, now(), now(), 'UTC', now(), "
            + source
            + ")");
  }

  private static String constraint(String name) {
    return "SELECT count(*) FROM pg_constraint WHERE conname = '" + name + "'";
  }

  private long changeset(String id) {
    return count("SELECT count(*) FROM journey.databasechangelog WHERE id = '" + id + "'");
  }

  private static String scanSourceColumn(String nullable) {
    return "SELECT count(*) FROM information_schema.columns WHERE table_schema = 'journey' "
        + "AND table_name = 'scan_audit' AND column_name = 'scan_source' "
        + "AND is_nullable = '"
        + nullable
        + "'";
  }

  /** Row count and a checksum of every column except the new one, per table. */
  private Map<String, Snapshot> snapshot() {
    Map<String, Snapshot> result = new LinkedHashMap<>();
    for (String table : TABLES) {
      String order = "processed_event".equals(table) ? "event_id" : "id";
      // V2 rewrites scan_audit by design; every other table must not even have been touched, so
      // the transaction id that last wrote each row (xmin) is part of its checksum.
      String touched = "scan_audit".equals(table) ? "" : " || '#' || t.xmin::text";
      String sql =
          "SELECT count(*), coalesce(md5(string_agg((to_jsonb(t) - 'scan_source')::text"
              + touched
              + ", '|' "
              + "ORDER BY "
              + order
              + ")), '') FROM journey."
              + table
              + " t";
      Object[] row =
          new TransactionTemplate(transactionManager)
              .execute(status -> (Object[]) entityManager.createNativeQuery(sql).getSingleResult());
      result.put(table, new Snapshot(((Number) row[0]).longValue(), (String) row[1]));
    }
    return result;
  }

  private record Snapshot(long rows, String checksum) {}
}
