// Copyright 2026 ThinkAI LLC
// SPDX-License-Identifier: Apache-2.0

package com.thinkaillc.schemasynchronizer;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.postgresql.ds.PGSimpleDataSource;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@LiveDatabase(engine = "PostgreSQL", properties = {
        "schema.test.jdbc.url",
        "schema.test.jdbc.user"})
class SchemaSynchronizerPostgresIntegrationTest {
    private final List<String> cleanupSchemas = new ArrayList<>();

    @TempDir
    Path tempDirectory;

    @AfterEach
    void removeTestSchemas() throws Exception {
        DataSource dataSource = dataSource();
        try (Connection connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            for (String schema : cleanupSchemas) {
                LiveTestSupport.requireTestNamespace(schema, "schema");
                statement.execute("DROP SCHEMA " + SqlIdentifiers.requireIdentifier(schema, "test schema")
                        + " CASCADE");
            }
        }
    }

    @Test
    void ledgersWithDistinctLegacyIdsLockConcurrentlyAndASharedIdSerializes() throws Exception {
        DataSource dataSource = dataSource();
        try (Connection first = dataSource.getConnection(); Connection second = dataSource.getConnection()) {
            first.setAutoCommit(false);
            second.setAutoCommit(false);
            try {
                DialectSupport.acquireLock(first, DatabaseDialect.POSTGRESQL, "app", "history_a", 1L);
                long started = System.nanoTime();
                DialectSupport.acquireLock(second, DatabaseDialect.POSTGRESQL, "app", "history_b", 2L);
                assertThat(System.nanoTime() - started).as("distinct ids do not wait")
                        .isLessThan(java.util.concurrent.TimeUnit.SECONDS.toNanos(5));
                assertThat(LiveTestSupport.scalar(second, "SELECT pg_try_advisory_xact_lock(1)"))
                        .as("the 1.x key a shared id selects is held").isEqualTo("f");
            } finally {
                first.rollback();
                second.rollback();
            }
        }
    }

    @Test
    void schemaAndTableNamesAreNotMetadataPatterns() throws Exception {
        DataSource dataSource = dataSource();
        String schema = uniqueSchema("pattern_case");
        createSchema(dataSource, schema);
        // `_` is a metadata wildcard: neither the sibling schema nor the sibling table may be read.
        String siblingSchema = schema.replaceFirst("_", "x");
        createSchema(dataSource, siblingSchema);
        try (Connection connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE " + schema + ".pt_items (id BIGINT PRIMARY KEY)");
            statement.execute("CREATE TABLE " + schema + ".ptxitems (id BIGINT PRIMARY KEY, extra INTEGER)");
            statement.execute("CREATE TABLE " + siblingSchema + ".ghost_table (id INTEGER)");
            statement.execute("CREATE TABLE " + siblingSchema + ".pt_items (id BIGINT PRIMARY KEY, ghost INTEGER NOT NULL)");
        }
        List<SchemaDefinition.ColumnDef> columns = List.of(new SchemaDefinition.ColumnDef("id", "BIGINT NOT NULL"),
                new SchemaDefinition.ColumnDef("extra", "INTEGER"));
        SchemaDefinition declared = new SchemaDefinition(2, "postgresql", Map.of(
                "pt_items", new SchemaDefinition.TableDef(
                        "CREATE TABLE IF NOT EXISTS pt_items (id BIGINT PRIMARY KEY)", columns, List.of()),
                "ptxitems", new SchemaDefinition.TableDef(
                        "CREATE TABLE IF NOT EXISTS ptxitems (id BIGINT PRIMARY KEY, extra INTEGER)", columns, List.of())),
                List.of());
        SchemaSynchronizer synchronizer = synchronizer(dataSource, schema, false);
        try (Connection connection = dataSource.getConnection()) {
            SchemaSynchronizationResult result = synchronizer.synchronizeWithResult(connection, declared);
            assertThat(result.columnsAdded()).isEqualTo(1);
            assertThat(result.pendingSql()).isEmpty();
        }
        try (Connection connection = dataSource.getConnection()) {
            assertThat(synchronizer.synchronizeWithResult(connection, declared).changed()).isFalse();
        }
    }

    @Test
    void appliesComplexChangesOnceAndRejectsChecksumDrift() throws Exception {
        DataSource dataSource = dataSource();
        String schema = uniqueSchema("apply_case");
        createSchema(dataSource, schema);
        SchemaDefinition definition = definition("CHECK (score >= 0)");
        SchemaSynchronizer synchronizer = synchronizer(dataSource, schema, false);

        try (Connection connection = dataSource.getConnection()) {
            SchemaSynchronizationResult first = synchronizer.synchronizeWithResult(connection, definition);
            assertThat(first.changeSetsApplied()).isEqualTo(1);
        }
        try (Connection connection = dataSource.getConnection()) {
            SchemaSynchronizationResult replay = synchronizer.synchronizeWithResult(connection, definition);
            assertThat(replay.changeSetsApplied()).isZero();
            assertThat(queryLong(connection, "SELECT count(*) FROM " + schema
                    + ".schema_synchronizer_history")).isEqualTo(1);
            assertThat(queryLong(connection, "SELECT count(*) FROM " + schema + ".child WHERE score = 0"))
                    .isEqualTo(1);
            assertThat(queryLong(connection, "SELECT count(*) FROM pg_constraint c JOIN pg_namespace n "
                    + "ON n.oid = c.connamespace WHERE n.nspname = '" + schema + "' "
                    + "AND conname = 'ck_child_score'"))
                    .isEqualTo(1);
            assertThat(queryLong(connection, "SELECT count(*) FROM pg_trigger WHERE tgname = 'trg_child_guard'"))
                    .isEqualTo(1);
        }

        SchemaDefinition edited = definition("CHECK (score > 0)");
        try (Connection connection = dataSource.getConnection()) {
            assertThatThrownBy(() -> synchronizer.synchronizeWithResult(connection, edited))
                    .isInstanceOf(SchemaDefinitionException.class)
                    .hasMessageContaining("checksum mismatch");
        }
    }

    @Test
    void serializedDefinitionReplaysIntoDifferentSchema() throws Exception {
        DataSource dataSource = dataSource();
        String sourceSchema = uniqueSchema("serialize_source");
        String targetSchema = uniqueSchema("serialize_target");
        createSchema(dataSource, sourceSchema);
        createSchema(dataSource, targetSchema);
        try (Connection connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE " + sourceSchema
                    + ".accounts (id BIGINT PRIMARY KEY, email VARCHAR(320) NOT NULL UNIQUE, display_name TEXT, "
                    + "created_at TIMESTAMP(3), seen_at TIMESTAMPTZ, clock TIME(0), stamped TIMESTAMP, "
                    + "mask BIT(3) DEFAULT B'101', flags BIT VARYING(5))");
            statement.execute("CREATE INDEX idx_accounts_display_name ON "
                    + sourceSchema + ".accounts (display_name)");
        }

        Path definitionPath = tempDirectory.resolve("schema-definition.json");
        try (Connection connection = dataSource.getConnection()) {
            SchemaSnapshotWriter.writeSnapshot(connection, sourceSchema, definitionPath);
        }
        SchemaDefinition definition = new ObjectMapper().readValue(definitionPath.toFile(), SchemaDefinition.class);

        assertThat(definition.tables().get("accounts").indexes())
                .containsExactly(
                        "CREATE UNIQUE INDEX IF NOT EXISTS \"accounts_email_key\" ON \"accounts\" (\"email\")",
                        "CREATE INDEX IF NOT EXISTS \"idx_accounts_display_name\" ON \"accounts\" (\"display_name\")");
        try (Connection connection = dataSource.getConnection()) {
            SchemaSynchronizationResult result = synchronizer(dataSource, targetSchema, false)
                    .synchronizeWithResult(connection, definition);
            assertThat(result.pendingSql()).isEmpty();
            assertThat(tableExists(connection, targetSchema, "accounts")).isTrue();
            statement(connection, "INSERT INTO " + targetSchema + ".accounts (id, email) VALUES (1, 'a@example.com')");
            assertThatThrownBy(() -> statement(connection, "INSERT INTO " + targetSchema
                    + ".accounts (id, email) VALUES (2, 'a@example.com')"))
                    .isInstanceOf(SQLException.class);
        }
        try (Connection connection = dataSource.getConnection()) {
            for (String schema : List.of(sourceSchema, targetSchema)) {
                SchemaSynchronizationResult strict = synchronizer(dataSource, schema, false)
                        .synchronizeWithResult(connection, definition);
                assertThat(strict.pendingSql()).as(schema).isEmpty();
                assertThat(strict.changed()).as(schema).isFalse();
            }
        }
        assertThat(definition.tables().get("accounts").columns())
                .anyMatch(column -> column.name().equals("created_at") && column.definition().startsWith("TIMESTAMP(3)"))
                .anyMatch(column -> column.name().equals("clock") && column.definition().startsWith("TIME(0)"))
                .anyMatch(column -> column.name().equals("stamped") && column.definition().equals("TIMESTAMP"));

        SchemaDefinition.TableDef accounts = definition.tables().get("accounts");
        List<SchemaDefinition.ColumnDef> handWritten = accounts.columns().stream()
                .map(column -> switch (column.name()) {
                    case "mask" -> new SchemaDefinition.ColumnDef("mask", "BIT(3) DEFAULT B'101'");
                    case "flags" -> new SchemaDefinition.ColumnDef("flags", "BIT VARYING(5)");
                    default -> column;
                })
                .toList();
        try (Connection connection = dataSource.getConnection()) {
            SchemaSynchronizationResult declared = synchronizer(dataSource, sourceSchema, false).synchronizeWithResult(
                    connection, new SchemaDefinition(definition.formatVersion(), definition.dialect(),
                            Map.of("accounts", new SchemaDefinition.TableDef(accounts.createSql(), handWritten,
                                    accounts.indexes())), List.of()));
            assertThat(declared.pendingSql()).isEmpty();
            assertThat(declared.changed()).isFalse();
        }
        List<SchemaDefinition.ColumnDef> finer = accounts.columns().stream()
                .map(column -> column.name().equals("created_at")
                        ? new SchemaDefinition.ColumnDef("created_at", "TIMESTAMP(6)") : column)
                .toList();
        SchemaDefinition drifted = new SchemaDefinition(definition.formatVersion(), definition.dialect(),
                Map.of("accounts", new SchemaDefinition.TableDef(accounts.createSql(), finer, accounts.indexes())),
                List.of());
        try (Connection connection = dataSource.getConnection()) {
            SchemaSynchronizationResult result = reportingSynchronizer(dataSource, targetSchema)
                    .synchronizeWithResult(connection, drifted);
            assertThat(result.columnsAltered()).isZero();
            assertThat(result.pendingSql()).anyMatch(sql -> sql.contains("ALTER COLUMN \"created_at\" TYPE TIMESTAMP(6)"));
        }
    }

    @Test
    void serializerFailsClosedForPostgresExcludeConstraint() throws Exception {
        DataSource dataSource = dataSource();
        String sourceSchema = uniqueSchema("serialize_exclude");
        createSchema(dataSource, sourceSchema);
        try (Connection connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE " + sourceSchema
                    + ".reservations (slot int4range, EXCLUDE USING gist (slot WITH &&))");
        }

        Path definitionPath = tempDirectory.resolve("exclude-schema-definition.json");
        assertThatThrownBy(() -> {
            try (Connection connection = dataSource.getConnection()) {
                SchemaSnapshotWriter.writeSnapshot(connection, sourceSchema, definitionPath);
            }
        })
                .isInstanceOf(SchemaDefinitionException.class)
                .hasMessageContaining("EXCLUDE constraint")
                .hasMessageContaining("ordered change set");
    }

    @Test
    void dryRunAndDestructivePreflightDoNotMutateDatabase() throws Exception {
        DataSource dataSource = dataSource();
        String schema = uniqueSchema("dry_run_case");
        createSchema(dataSource, schema);
        SchemaSynchronizer dryRun = synchronizer(dataSource, schema, true);
        try (Connection connection = dataSource.getConnection()) {
            SchemaSynchronizationResult result = dryRun.synchronizeWithResult(connection, definition("CHECK (score >= 0)"));
            assertThat(result.plannedSql()).isNotEmpty();
            assertThat(tableExists(connection, schema, "child")).isFalse();
            assertThat(tableExists(connection, schema, "schema_synchronizer_history")).isFalse();
        }

        SchemaDefinition destructive = new SchemaDefinition(Map.of(), List.of(
                new SchemaDefinition.ChangeSet("bad", "must fail", List.of("DROP TABLE child"))));
        try (Connection connection = dataSource.getConnection()) {
            assertThatThrownBy(() -> synchronizer(dataSource, schema, false)
                    .synchronizeWithResult(connection, destructive))
                    .isInstanceOf(SchemaDefinitionException.class)
                    .hasMessageContaining("not allowed");
            assertThat(tableExists(connection, schema, "schema_synchronizer_history")).isFalse();
        }
    }

    @Test
    void adoptsPartialChangeWhenSomeStatementsAlreadyExist() throws Exception {
        DataSource dataSource = dataSource();
        String schema = uniqueSchema("partial_adopt");
        createSchema(dataSource, schema);
        SchemaDefinition definition = new SchemaDefinition(2, "postgresql", Map.of(), List.of(
                new SchemaDefinition.ChangeSet(
                        "001-two-checks",
                        "two check constraints; one may already exist from a prior tool",
                        List.of(
                                "CREATE TABLE widgets (id UUID PRIMARY KEY, score INTEGER, weight INTEGER)",
                                "ALTER TABLE widgets ADD CONSTRAINT ck_widgets_score CHECK (score IS NULL OR score >= 0)",
                                "ALTER TABLE widgets ADD CONSTRAINT ck_widgets_weight CHECK (weight IS NULL OR weight >= 0)"
                        ),
                        "SELECT count(*) = 2 FROM pg_constraint c JOIN pg_class r ON r.oid = c.conrelid "
                                + "JOIN pg_namespace n ON n.oid = r.relnamespace "
                                + "WHERE n.nspname = current_schema() AND r.relname = 'widgets' "
                                + "AND c.conname IN ('ck_widgets_score', 'ck_widgets_weight')",
                        SchemaDefinition.ChangeSet.Phase.AFTER_SCHEMA
                )));

        try (Connection connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute("SET search_path TO " + schema);
            statement.execute("CREATE TABLE widgets (id UUID PRIMARY KEY, score INTEGER, weight INTEGER)");
            statement.execute("ALTER TABLE widgets ADD CONSTRAINT ck_widgets_score CHECK (score IS NULL OR score >= 0)");
        }

        try (Connection connection = dataSource.getConnection()) {
            SchemaSynchronizationResult result = synchronizer(dataSource, schema, false)
                    .synchronizeWithResult(connection, definition);
            assertThat(result.changeSetsApplied()).isEqualTo(1);
            assertThat(queryLong(connection, "SELECT count(*) FROM " + schema
                    + ".schema_synchronizer_history")).isEqualTo(1);
            assertThat(queryLong(connection, "SELECT count(*) FROM pg_constraint c "
                    + "JOIN pg_class r ON r.oid = c.conrelid "
                    + "JOIN pg_namespace n ON n.oid = r.relnamespace "
                    + "WHERE n.nspname = '" + schema + "' AND r.relname = 'widgets' "
                    + "AND c.conname IN ('ck_widgets_score', 'ck_widgets_weight')"))
                    .isEqualTo(2);
        }

        try (Connection connection = dataSource.getConnection()) {
            SchemaSynchronizationResult replay = synchronizer(dataSource, schema, false)
                    .synchronizeWithResult(connection, definition);
            assertThat(replay.changeSetsApplied()).isZero();
        }
    }

    @Test
    void adoptsOnlyWhenVerificationProvesExistingChange() throws Exception {
        DataSource dataSource = dataSource();
        String schema = uniqueSchema("adopt_case");
        createSchema(dataSource, schema);
        SchemaDefinition definition = definition("CHECK (score >= 0)");
        try (Connection connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute("SET search_path TO " + schema);
            for (String sql : definition.changes().get(0).statements()) {
                statement.execute(sql);
            }
        }

        try (Connection connection = dataSource.getConnection()) {
            SchemaSynchronizationResult adopted = synchronizer(dataSource, schema, false)
                    .synchronizeWithResult(connection, definition);
            assertThat(adopted.changeSetsApplied()).isEqualTo(1);
            assertThat(queryLong(connection, "SELECT count(*) FROM " + schema
                    + ".schema_synchronizer_history")).isEqualTo(1);
        }
    }

    @Test
    void advisoryLockSerializesConcurrentStartup() throws Exception {
        DataSource dataSource = dataSource();
        String schema = uniqueSchema("concurrent_case");
        createSchema(dataSource, schema);
        SchemaDefinition definition = definition("CHECK (score >= 0)");
        SchemaSynchronizer synchronizer = synchronizer(dataSource, schema, false);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> applyAfterSignal(dataSource, synchronizer, definition, ready, start));
            var second = executor.submit(() -> applyAfterSignal(dataSource, synchronizer, definition, ready, start));
            ready.await();
            start.countDown();
            assertThat(first.get().changeSetsApplied() + second.get().changeSetsApplied()).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
        try (Connection connection = dataSource.getConnection()) {
            assertThat(queryLong(connection, "SELECT count(*) FROM " + schema
                    + ".schema_synchronizer_history")).isEqualTo(1);
        }
    }

    @Test
    void advisoryLockSerializesDeclarativeOnlyStartup() throws Exception {
        DataSource dataSource = dataSource();
        String schema = uniqueSchema("declarative_concurrent_case");
        createSchema(dataSource, schema);
        SchemaDefinition definition = new SchemaDefinition(Map.of(
                "only_table", new SchemaDefinition.TableDef(
                        "CREATE TABLE only_table (id BIGINT PRIMARY KEY)",
                        List.of(new SchemaDefinition.ColumnDef("id", "BIGINT NOT NULL")),
                        List.of("CREATE UNIQUE INDEX IF NOT EXISTS only_table_pkey ON only_table (id)"))));
        SchemaSynchronizer synchronizer = synchronizer(dataSource, schema, false);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> applyAfterSignal(dataSource, synchronizer, definition, ready, start));
            var second = executor.submit(() -> applyAfterSignal(dataSource, synchronizer, definition, ready, start));
            ready.await();
            start.countDown();
            assertThat(first.get().tablesCreated() + second.get().tablesCreated()).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void rejectsRemovedAppliedChange() throws Exception {
        DataSource dataSource = dataSource();
        String schema = uniqueSchema("removed_change_case");
        createSchema(dataSource, schema);
        SchemaSynchronizer synchronizer = synchronizer(dataSource, schema, false);
        try (Connection connection = dataSource.getConnection()) {
            synchronizer.synchronizeWithResult(connection, definition("CHECK (score >= 0)"));
        }
        try (Connection connection = dataSource.getConnection()) {
            assertThatThrownBy(() -> synchronizer.synchronizeWithResult(
                    connection, new SchemaDefinition(Map.of(), List.of())))
                    .isInstanceOf(SchemaDefinitionException.class)
                    .hasMessageContaining("missing from the immutable ledger");
        }
    }

    @Test
    void preservesCallerTransactionOnSuccessAndFailure() throws Exception {
        DataSource dataSource = dataSource();
        String schema = uniqueSchema("caller_transaction_case");
        createSchema(dataSource, schema);
        SchemaSynchronizer synchronizer = synchronizer(dataSource, schema, false);
        try (Connection connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            statement.execute("SET LOCAL search_path TO pg_catalog, public");
            String originalSearchPath = queryString(connection, "SHOW search_path");
            statement.execute("CREATE TABLE " + schema + ".caller_work (value INTEGER)");
            statement.execute("INSERT INTO " + schema + ".caller_work VALUES (1)");
            synchronizer.synchronizeWithResult(connection, new SchemaDefinition(Map.of(
                    "managed", new SchemaDefinition.TableDef("CREATE TABLE managed (id BIGINT PRIMARY KEY)",
                            List.of(new SchemaDefinition.ColumnDef("id", "BIGINT NOT NULL")),
                            List.of("CREATE UNIQUE INDEX IF NOT EXISTS managed_pkey ON managed (id)")),
                    "caller_work", new SchemaDefinition.TableDef(
                            "CREATE TABLE IF NOT EXISTS caller_work (value INTEGER)",
                            List.of(new SchemaDefinition.ColumnDef("value", "INTEGER")), List.of()))));
            assertThat(connection.getAutoCommit()).isFalse();
            assertThat(queryString(connection, "SHOW search_path")).isEqualTo(originalSearchPath);
            connection.rollback();
        }
        try (Connection connection = dataSource.getConnection()) {
            assertThat(tableExists(connection, schema, "caller_work")).isFalse();
            assertThat(tableExists(connection, schema, "managed")).isFalse();
        }

        try (Connection connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            statement.execute("SET search_path TO " + schema);
            statement.execute("CREATE TABLE caller_work (value INTEGER)");
            assertThatThrownBy(() -> synchronizer.synchronizeWithResult(connection,
                    new SchemaDefinition(Map.of(), List.of(new SchemaDefinition.ChangeSet(
                            "bad", "fails policy", List.of("DROP TABLE caller_work"))))))
                    .isInstanceOf(SchemaDefinitionException.class);
            statement.execute("INSERT INTO caller_work VALUES (2)");
            connection.commit();
        }
        try (Connection connection = dataSource.getConnection()) {
            assertThat(queryLong(connection, "SELECT count(*) FROM " + schema + ".caller_work"))
                    .isEqualTo(1);
        }
    }

    @Test
    void appliesPostSchemaConstraintsAfterCreatingFreshDeclarativeTables() throws Exception {
        DataSource dataSource = dataSource();
        String schema = uniqueSchema("post_schema_case");
        createSchema(dataSource, schema);
        SchemaDefinition definition = new SchemaDefinition(Map.of(
                "parent", new SchemaDefinition.TableDef(
                        "CREATE TABLE IF NOT EXISTS parent (id UUID PRIMARY KEY)",
                        List.of(new SchemaDefinition.ColumnDef("id", "UUID NOT NULL")),
                        List.of("CREATE UNIQUE INDEX IF NOT EXISTS parent_pkey ON parent (id)")),
                "child", new SchemaDefinition.TableDef(
                        "CREATE TABLE IF NOT EXISTS child (id UUID PRIMARY KEY, parent_id UUID)",
                        List.of(new SchemaDefinition.ColumnDef("id", "UUID NOT NULL"),
                                new SchemaDefinition.ColumnDef("parent_id", "UUID")),
                        List.of("CREATE UNIQUE INDEX IF NOT EXISTS child_pkey ON child (id)"))
        ), List.of(new SchemaDefinition.ChangeSet(
                "001-child-parent", "add the relationship after both tables exist",
                List.of("ALTER TABLE child ADD CONSTRAINT fk_child_parent "
                        + "FOREIGN KEY (parent_id) REFERENCES parent(id)"),
                "SELECT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_child_parent' "
                        + "AND conrelid = to_regclass('child'))",
                SchemaDefinition.ChangeSet.Phase.AFTER_SCHEMA)));

        try (Connection connection = dataSource.getConnection()) {
            SchemaSynchronizationResult result = synchronizer(dataSource, schema, false)
                    .synchronizeWithResult(connection, definition);
            assertThat(result.tablesCreated()).isEqualTo(2);
            assertThat(result.changeSetsApplied()).isEqualTo(1);
            assertThat(queryLong(connection, "SELECT count(*) FROM pg_constraint c JOIN pg_namespace n "
                    + "ON n.oid = c.connamespace WHERE n.nspname = '" + schema + "' "
                    + "AND conname = 'fk_child_parent'")).isEqualTo(1);
        }
    }

    @Test
    void rejectsIndexDefinitionDriftAndOrphanTables() throws Exception {
        DataSource dataSource = dataSource();
        String schema = uniqueSchema("drift_case");
        createSchema(dataSource, schema);
        SchemaDefinition definition = new SchemaDefinition(Map.of(
                "items", new SchemaDefinition.TableDef(
                        "CREATE TABLE IF NOT EXISTS items (id BIGINT PRIMARY KEY, code VARCHAR(20))",
                        List.of(new SchemaDefinition.ColumnDef("id", "BIGINT NOT NULL"),
                                new SchemaDefinition.ColumnDef("code", "VARCHAR(20)")),
                        List.of("CREATE UNIQUE INDEX IF NOT EXISTS items_pkey ON items (id)",
                                "CREATE INDEX IF NOT EXISTS idx_items_code ON items (code)"))));
        SchemaSynchronizer synchronizer = synchronizer(dataSource, schema, false);
        try (Connection connection = dataSource.getConnection()) {
            synchronizer.synchronizeWithResult(connection, definition);
            try (var statement = connection.createStatement()) {
                statement.execute("SET search_path TO " + schema);
                statement.execute("ALTER TABLE items DROP CONSTRAINT items_pkey");
            }
            assertThatThrownBy(() -> synchronizer.synchronizeWithResult(connection, definition))
                    .isInstanceOf(SchemaDefinitionException.class)
                    .hasMessageContaining("primary key is missing");
            SchemaSynchronizationResult missingPrimaryKey = reportingSynchronizer(dataSource, schema)
                    .synchronizeWithResult(connection, definition);
            assertThat(missingPrimaryKey.pendingSql()).contains(
                    "ALTER TABLE \"items\" ADD PRIMARY KEY (\"id\"); -- pending: primary key is missing");
            try (var statement = connection.createStatement()) {
                statement.execute("SET search_path TO " + schema);
                statement.execute("ALTER TABLE items ADD PRIMARY KEY (code)");
            }
            SchemaSynchronizationResult driftedPrimaryKey = reportingSynchronizer(dataSource, schema)
                    .synchronizeWithResult(connection, definition);
            assertThat(driftedPrimaryKey.pendingSql())
                    .anySatisfy(sql -> assertThat(sql)
                            .matches("ALTER TABLE \"items\" DROP CONSTRAINT \"items_pkey\\d*\";"))
                    .contains("ALTER TABLE \"items\" ADD PRIMARY KEY (\"id\");",
                            "ALTER TABLE \"items\" ALTER COLUMN \"code\" DROP NOT NULL;");
            try (var statement = connection.createStatement()) {
                statement.execute("SET search_path TO " + schema);
                for (String sql : driftedPrimaryKey.pendingSql()) {
                    if (!sql.startsWith("--")) {
                        statement.execute(sql);
                    }
                }
                statement.execute("DROP INDEX idx_items_code");
                statement.execute("CREATE INDEX idx_items_code ON items (id)");
            }
            assertThatThrownBy(() -> synchronizer.synchronizeWithResult(connection, definition))
                    .isInstanceOf(SchemaDefinitionException.class)
                    .hasMessageContaining("index definition drift");
            SchemaSynchronizationResult driftedIndex = reportingSynchronizer(dataSource, schema)
                    .synchronizeWithResult(connection, definition);
            assertThat(driftedIndex.pendingSql()).containsSubsequence(
                    "DROP INDEX IF EXISTS \"idx_items_code\";",
                    "CREATE INDEX IF NOT EXISTS \"idx_items_code\" ON \"items\" (\"code\");");
            try (var statement = connection.createStatement()) {
                statement.execute("SET search_path TO " + schema);
                statement.execute("DROP INDEX idx_items_code");
                statement.execute("CREATE INDEX idx_items_code ON items (code)");
                statement.execute("CREATE TABLE forgotten_table (value INTEGER)");
            }
            assertThatThrownBy(() -> synchronizer.synchronizeWithResult(connection, definition))
                    .isInstanceOf(SchemaDefinitionException.class)
                    .hasMessageContaining("table absent from definition");
            SchemaSynchronizationResult orphanTable = reportingSynchronizer(dataSource, schema)
                    .synchronizeWithResult(connection, definition);
            assertThat(orphanTable.pendingSql()).contains(
                    "DROP TABLE \"forgotten_table\"; -- pending: table absent from definition");
        }
    }

    @Test
    void doesNotTreatConstraintOwnedIndexesAsOrphans() throws Exception {
        DataSource dataSource = dataSource();
        String schema = uniqueSchema("constraint_index_case");
        createSchema(dataSource, schema);
        SchemaDefinition definition = new SchemaDefinition(Map.of(
                "accounts", new SchemaDefinition.TableDef(
                        "CREATE TABLE IF NOT EXISTS accounts (id BIGINT PRIMARY KEY, email VARCHAR(100) UNIQUE)",
                        List.of(new SchemaDefinition.ColumnDef("id", "BIGINT NOT NULL"),
                                new SchemaDefinition.ColumnDef("email", "VARCHAR(100)")),
                        List.of())));

        try (Connection connection = dataSource.getConnection()) {
            SchemaSynchronizationResult result = synchronizer(dataSource, schema, false)
                    .synchronizeWithResult(connection, definition);
            assertThat(result.pendingSql()).isEmpty();
        }
    }

    @Test
    void failsClosedAndPrintsReplacementForPartialIndexPredicateDrift() throws Exception {
        DataSource dataSource = dataSource();
        String schema = uniqueSchema("predicate_drift_case");
        createSchema(dataSource, schema);
        SchemaDefinition definition = new SchemaDefinition(Map.of(
                "jobs", new SchemaDefinition.TableDef(
                        "CREATE TABLE IF NOT EXISTS jobs (id BIGINT PRIMARY KEY, status VARCHAR(20))",
                        List.of(new SchemaDefinition.ColumnDef("id", "BIGINT NOT NULL"),
                                new SchemaDefinition.ColumnDef("status", "VARCHAR(20)")),
                        List.of("CREATE UNIQUE INDEX IF NOT EXISTS uq_jobs_active ON jobs (id) "
                                + "WHERE status = 'ACTIVE'"))));
        try (Connection connection = dataSource.getConnection()) {
            synchronizer(dataSource, schema, false).synchronizeWithResult(connection, definition);
            try (var statement = connection.createStatement()) {
                statement.execute("SET search_path TO " + schema);
                statement.execute("DROP INDEX uq_jobs_active");
                statement.execute("CREATE UNIQUE INDEX uq_jobs_active ON jobs (id) WHERE status = 'DRAFT'");
            }
            assertThatThrownBy(() -> synchronizer(dataSource, schema, false)
                    .synchronizeWithResult(connection, definition))
                    .isInstanceOf(SchemaDefinitionException.class)
                    .hasMessageContaining("index definition drift");
            SchemaSynchronizationResult plan = reportingSynchronizer(dataSource, schema)
                    .synchronizeWithResult(connection, definition);
            assertThat(plan.pendingSql()).containsSubsequence(
                    "DROP INDEX IF EXISTS \"uq_jobs_active\";",
                    "CREATE UNIQUE INDEX IF NOT EXISTS \"uq_jobs_active\" ON \"jobs\" (\"id\") WHERE status = 'ACTIVE';");
        }
    }

    @Test
    void multiStatementRoutineBodiesAreOneChangeSetStatement() throws Exception {
        DataSource dataSource = dataSource();
        String schema = uniqueSchema("routine_body_case");
        createSchema(dataSource, schema);
        SchemaDefinition definition = new SchemaDefinition(Map.of(), List.of(new SchemaDefinition.ChangeSet(
                "001-routine-bodies", "BEGIN ATOMIC and PL/pgSQL bodies", List.of(
                "CREATE TABLE counters (id INTEGER PRIMARY KEY, hits INTEGER NOT NULL, note TEXT)",
                "INSERT INTO counters (id, hits) VALUES (1, 0)",
                "CREATE FUNCTION bump_counter(step integer) RETURNS integer LANGUAGE sql BEGIN ATOMIC "
                        + "UPDATE counters SET hits = hits + step WHERE id = 1; "
                        + "UPDATE counters SET note = CASE WHEN step > 0 THEN 'a;b' ELSE 'c' END WHERE id = 1; "
                        + "SELECT hits FROM counters WHERE id = 1; END",
                "CREATE FUNCTION counters_note() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN "
                        + "IF NEW.note IS NULL THEN NEW.note := 'set;by trigger'; END IF; RETURN NEW; END $$",
                "CREATE TRIGGER trg_counters_note BEFORE INSERT ON counters "
                        + "FOR EACH ROW EXECUTE FUNCTION counters_note()"
        ), "SELECT to_regprocedure('bump_counter(integer)') IS NOT NULL")));
        try (Connection connection = dataSource.getConnection()) {
            assertThat(synchronizer(dataSource, schema, false).synchronizeWithResult(connection, definition)
                    .changeSetsApplied()).isEqualTo(1);
            assertThat(queryLong(connection, "SELECT " + schema + ".bump_counter(2)")).isEqualTo(2);
            assertThat(queryString(connection, "SELECT note FROM " + schema + ".counters WHERE id = 1"))
                    .isEqualTo("a;b");
            statement(connection, "INSERT INTO " + schema + ".counters (id, hits) VALUES (2, 0)");
            assertThat(queryString(connection, "SELECT note FROM " + schema + ".counters WHERE id = 2"))
                    .isEqualTo("set;by trigger");
        }
    }

    @Test
    void aliasQualifiedColumnsApply() throws Exception {
        DataSource dataSource = dataSource();
        String schema = uniqueSchema("alias_columns_case");
        createSchema(dataSource, schema);
        try (Connection connection = dataSource.getConnection()) {
            statement(connection, "CREATE TABLE " + schema + ".items (id INTEGER PRIMARY KEY, note TEXT, "
                    + "tag_id INTEGER)");
            statement(connection, "CREATE TABLE " + schema + ".tags (id INTEGER PRIMARY KEY, name TEXT)");
        }
        SchemaDefinition definition = new SchemaDefinition(Map.of(), List.of(new SchemaDefinition.ChangeSet(
                "001-alias-columns", "alias- and table-qualified columns", List.of(
                "INSERT INTO tags (id, name) VALUES (1, 't1')",
                "INSERT INTO items (id, tag_id) VALUES (1, 1)",
                "UPDATE items AS i SET note = t.name FROM tags t WHERE t.id = i.tag_id",
                "UPDATE items AS i SET note = i.note || '!' WHERE i.id = 1",
                "INSERT INTO items AS i (id, note) VALUES (1, 'x') "
                        + "ON CONFLICT (id) DO UPDATE SET note = i.note || EXCLUDED.note"
        ), "SELECT EXISTS (SELECT 1 FROM items WHERE id = 1 AND note = 't1!x')")));
        try (Connection connection = dataSource.getConnection()) {
            assertThat(synchronizer(dataSource, schema, false).synchronizeWithResult(connection, definition)
                    .changeSetsApplied()).isEqualTo(1);
            assertThat(queryString(connection, "SELECT note FROM " + schema + ".items WHERE id = 1"))
                    .isEqualTo("t1!x");
        }
    }

    @Test
    void expressionKeywordsRelationLiteralsRecordsAndCteWritesApply() throws Exception {
        DataSource dataSource = dataSource();
        String schema = uniqueSchema("expression_forms_case");
        createSchema(dataSource, schema);
        try (Connection connection = dataSource.getConnection()) {
            statement(connection, "CREATE TABLE " + schema + ".items (id INTEGER PRIMARY KEY, note TEXT, "
                    + "qty INTEGER, created_at TIMESTAMP)");
            statement(connection, "CREATE TABLE " + schema + ".staged (id INTEGER, note TEXT)");
            statement(connection, "CREATE TABLE " + schema + ".tags (id INTEGER, name TEXT)");
            statement(connection, "INSERT INTO " + schema + ".staged VALUES (1, ' a ')");
            statement(connection, "INSERT INTO " + schema + ".tags VALUES (2, 'b')");
            statement(connection, "CREATE SEQUENCE " + schema + ".items_seq");
        }
        SchemaDefinition definition = new SchemaDefinition(Map.of(), List.of(new SchemaDefinition.ChangeSet(
                "001-expression-forms", "set branches, operand FROM/FOR, relation literals, records, CTE writes",
                List.of(
                "INSERT INTO items (id, note) SELECT s.id, s.note FROM staged s UNION ALL SELECT t.id, t.name FROM tags t",
                "UPDATE items i SET note = TRIM(BOTH ' ' FROM i.note), created_at = TIMESTAMP '2020-01-02' "
                        + "WHERE i.note IS DISTINCT FROM TRIM(i.note)",
                "UPDATE items i SET qty = EXTRACT(YEAR FROM i.created_at) WHERE i.created_at IS NOT NULL",
                "UPDATE items i SET note = i.note || SUBSTRING('xyz' FROM 2 FOR 1) WHERE i.id = 1",
                "INSERT INTO items AS x (id, note) VALUES (2, 'c') ON CONFLICT (id) "
                        + "DO UPDATE SET note = EXCLUDED.note WHERE x.note IS DISTINCT FROM EXCLUDED.note",
                "UPDATE items SET qty = nextval('items_seq') WHERE id = 2",
                "ALTER TABLE items ALTER COLUMN qty SET DEFAULT nextval('" + schema + ".items_seq'::regclass)",
                "WITH c AS (SELECT id FROM staged) UPDATE items SET note = note || '!' WHERE id IN (SELECT c.id FROM c)",
                "WITH s AS (SELECT 3 AS id) INSERT INTO items (id, note) SELECT s.id, 'w' FROM s",
                "CREATE FUNCTION bump(p integer) RETURNS integer AS $$ DECLARE v items%ROWTYPE; "
                        + "n items.note%TYPE; r record; total integer := 0; BEGIN "
                        + "SELECT * INTO v FROM items WHERE id = 1; n := v.note; "
                        + "FOR r IN SELECT id FROM items LOOP total := total + r.id; END LOOP; "
                        + "RETURN bump.p + total + length(n) - length(v.note); END $$ LANGUAGE plpgsql",
                "CREATE FUNCTION fill() RETURNS trigger AS $$ BEGIN NEW.qty := 99; RETURN NEW; END $$ "
                        + "LANGUAGE plpgsql",
                "CREATE TRIGGER items_fill BEFORE UPDATE ON items FOR EACH ROW "
                        + "WHEN (OLD.note IS DISTINCT FROM NEW.note) EXECUTE FUNCTION fill()",
                "UPDATE items SET qty = bump(qty) WHERE id = 2",
                "UPDATE items SET note = 'z' WHERE id = 3"
        ), "SELECT EXISTS (SELECT 1 FROM items WHERE id = 1 AND note = 'ay!' AND qty = 2020) "
                + "AND EXISTS (SELECT 1 FROM items WHERE id = 2 AND note = 'c' AND qty = 7) "
                + "AND EXISTS (SELECT 1 FROM items WHERE id = 3 AND note = 'z' AND qty = 99)")));
        try (Connection connection = dataSource.getConnection()) {
            assertThat(synchronizer(dataSource, schema, false).synchronizeWithResult(connection, definition)
                    .changeSetsApplied()).isEqualTo(1);
        }
    }

    @Test
    void serialSequencesRegCastsLabelsAndRecordFieldsApply() throws Exception {
        DataSource dataSource = dataSource();
        String schema = uniqueSchema("serial_labels_case");
        createSchema(dataSource, schema);
        try (Connection connection = dataSource.getConnection()) {
            statement(connection, "CREATE TABLE " + schema + ".items (id SERIAL PRIMARY KEY, note TEXT, qty INTEGER)");
            statement(connection, "INSERT INTO " + schema + ".items (id, note, qty) VALUES (5, 'a', 0), (6, 'b', 0)");
            statement(connection, "CREATE TYPE " + schema + ".address AS (city TEXT)");
            statement(connection, "CREATE TYPE " + schema + ".holder AS (addr " + schema + ".address)");
        }
        SchemaDefinition definition = new SchemaDefinition(Map.of(), List.of(new SchemaDefinition.ChangeSet(
                "001-serial-labels", "serial sequence reset, qualified reg casts, labels, nested record fields",
                List.of(
                "SELECT setval(pg_get_serial_sequence('items', 'id'), coalesce(max(id), 1)) FROM items",
                "INSERT INTO items (id, note) VALUES (nextval(pg_get_serial_sequence('" + schema + ".items', 'id')), 'c')",
                "UPDATE items SET qty = currval(pg_catalog.pg_get_serial_sequence('items', 'id')) WHERE note = 'c'",
                "UPDATE items SET note = note || '!' WHERE pg_relation_size('items'::pg_catalog.regclass) >= 0 "
                        + "AND to_regclass('" + schema + ".items') = CAST('items' AS pg_catalog.regclass) "
                        + "AND 'character varying'::regtype IS NOT NULL AND id = 5",
                "CREATE FUNCTION city_of(r holder) RETURNS text AS $$ <<blk>> DECLARE n int := 1; s items; BEGIN "
                        + "SELECT * INTO s FROM items WHERE id = 5; DECLARE n int := 2; BEGIN "
                        + "RETURN (r.addr).city || blk.n || blk.s.note; END; END $$ LANGUAGE plpgsql",
                "UPDATE items SET note = city_of(ROW(ROW('x')::address)::holder) WHERE id = 6"
        ), "SELECT EXISTS (SELECT 1 FROM items WHERE id = 7 AND note = 'c' AND qty = 7) "
                + "AND EXISTS (SELECT 1 FROM items WHERE id = 5 AND note = 'a!') "
                + "AND EXISTS (SELECT 1 FROM items WHERE id = 6 AND note = 'x1a!')")));
        try (Connection connection = dataSource.getConnection()) {
            assertThat(synchronizer(dataSource, schema, false).synchronizeWithResult(connection, definition)
                    .changeSetsApplied()).isEqualTo(1);
        }
    }

    @Test
    void oidMetadataCallsStringSpellingsAndSchemaQualifiedTypesApply() throws Exception {
        DataSource dataSource = dataSource();
        String schema = uniqueSchema("type_positions_case");
        createSchema(dataSource, schema);
        try (Connection connection = dataSource.getConnection()) {
            statement(connection, "CREATE TABLE " + schema + ".items (id SERIAL PRIMARY KEY, note TEXT, qty INTEGER)");
            statement(connection, "INSERT INTO " + schema + ".items (id, note, qty) VALUES (1, 'a', 3), (2, 'b', 0)");
        }
        SchemaDefinition definition = new SchemaDefinition(Map.of(), List.of(new SchemaDefinition.ChangeSet(
                "001-type-positions", "oid metadata calls, reg* string spellings, qualified parameter and variable types",
                List.of(
                "CREATE INDEX items_brin ON items USING brin (qty)",
                "SELECT brin_summarize_new_values('items_brin')",
                "UPDATE items SET qty = qty + (SELECT count(*) FROM pg_class c JOIN pg_index i ON i.indrelid = c.oid "
                        + "WHERE c.oid = $$items$$::regclass AND pg_relation_size(c.oid) >= 0 "
                        + "AND pg_total_relation_size(c.oid) >= pg_indexes_size(c.oid) "
                        + "AND pg_index_has_property(i.indexrelid, 'clusterable') "
                        + "AND i.indexrelid::regclass IS NOT NULL) WHERE id = 1",
                "UPDATE items SET note = note || '!' WHERE ('items')::regclass = 'items'::text::regclass "
                        + "AND 'items'::varchar(20)::regclass = E'" + schema + ".items'::regclass "
                        + "AND 'items'::character varying::regclass = CAST(('items') AS regclass) AND id = 2",
                "SELECT setval(E'" + schema + ".items_id_seq', 2)",
                "INSERT INTO items (id, note, qty) VALUES (nextval($$items_id_seq$$), 'c', 0)",
                "CREATE FUNCTION qty_of(" + schema + ".items) RETURNS int AS $$ BEGIN RETURN $1.qty; END $$ "
                        + "LANGUAGE plpgsql",
                "CREATE FUNCTION tagged(p " + schema + ".items, q int DEFAULT 1) RETURNS " + schema + ".items AS $$ "
                        + "DECLARE x " + schema + ".items; y items.note%TYPE; BEGIN x := p; "
                        + "y := CAST(p.note AS text) COLLATE \"C\"; x.note := y || q::text; RETURN x; END $$ "
                        + "LANGUAGE plpgsql",
                "UPDATE items i SET qty = qty_of(i) + 1, note = (tagged(i)).note WHERE i.id = 3"
        ), "SELECT EXISTS (SELECT 1 FROM items WHERE id = 1 AND qty = 4) "
                + "AND EXISTS (SELECT 1 FROM items WHERE id = 2 AND note = 'b!') "
                + "AND EXISTS (SELECT 1 FROM items WHERE id = 3 AND note = 'c1' AND qty = 1)")));
        try (Connection connection = dataSource.getConnection()) {
            assertThat(synchronizer(dataSource, schema, false).synchronizeWithResult(connection, definition)
                    .changeSetsApplied()).isEqualTo(1);
        }
    }

    @Test
    void schemaQualifiedAnchorsAndDeclarationDefaultsApply() throws Exception {
        DataSource dataSource = dataSource();
        String schema = uniqueSchema("anchors_case");
        createSchema(dataSource, schema);
        try (Connection connection = dataSource.getConnection()) {
            statement(connection, "CREATE TABLE " + schema + ".items (id INTEGER PRIMARY KEY, note TEXT, qty INTEGER)");
            statement(connection, "INSERT INTO " + schema + ".items (id, note, qty) VALUES (1, 'a', 4)");
        }
        SchemaDefinition definition = new SchemaDefinition(Map.of(), List.of(new SchemaDefinition.ChangeSet(
                "001-anchors", "three-part %TYPE anchors and = defaults in declarations",
                List.of(
                "CREATE FUNCTION anchored(p " + schema + ".items.note%TYPE, r items) RETURNS " + schema
                        + ".items.qty%TYPE AS $$ DECLARE n " + schema + ".items.note%TYPE = p; m items.note%TYPE; "
                        + "q int = r.qty; k CONSTANT int = 2; z int NOT NULL = r.qty; "
                        + "s text COLLATE \"C\" = r.note; BEGIN m := n || s; "
                        + "RETURN q * k + z + length(m); END $$ LANGUAGE plpgsql",
                "UPDATE items i SET qty = anchored('xy', i) WHERE i.id = 1"
        ), "SELECT EXISTS (SELECT 1 FROM items WHERE id = 1 AND qty = 15)")));
        try (Connection connection = dataSource.getConnection()) {
            assertThat(synchronizer(dataSource, schema, false).synchronizeWithResult(connection, definition)
                    .changeSetsApplied()).isEqualTo(1);
        }
    }

    @Test
    void functionSearchPathPinnedToConfiguredSchemaApplies() throws Exception {
        DataSource dataSource = dataSource();
        String schema = uniqueSchema("routine_path_case");
        createSchema(dataSource, schema);
        try (Connection connection = dataSource.getConnection()) {
            statement(connection, "CREATE TABLE " + schema + ".items (id INTEGER PRIMARY KEY, qty INTEGER)");
            statement(connection, "INSERT INTO " + schema + ".items (id, qty) VALUES (1, 7)");
        }
        SchemaDefinition definition = new SchemaDefinition(Map.of(), List.of(new SchemaDefinition.ChangeSet(
                "001-pinned-path", "function-level search_path naming only the configured schema",
                List.of(
                "CREATE FUNCTION pinned_qty() RETURNS int SECURITY DEFINER SET search_path = " + schema
                        + ", pg_temp AS $$ BEGIN RETURN (SELECT qty FROM items WHERE id = 1); END $$ LANGUAGE plpgsql",
                "CREATE FUNCTION pinned_quoted() RETURNS int LANGUAGE sql SET search_path TO pg_catalog, \""
                        + schema + "\" AS 'SELECT qty + 1 FROM items WHERE id = 1'"
        ), "SELECT to_regprocedure('pinned_qty()') IS NOT NULL AND to_regprocedure('pinned_quoted()') IS NOT NULL")));
        try (Connection connection = dataSource.getConnection()) {
            assertThat(synchronizer(dataSource, schema, false).synchronizeWithResult(connection, definition)
                    .changeSetsApplied()).isEqualTo(1);
        }
        try (Connection connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute("SET search_path TO pg_catalog");
            try (var rows = statement.executeQuery("SELECT " + schema + ".pinned_qty(), " + schema + ".pinned_quoted()")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getInt(1)).isEqualTo(7);
                assertThat(rows.getInt(2)).isEqualTo(8);
            }
        }
    }

    @Test
    void sqlPolicyGatesOnlyChangeSetsMissingFromHistory() throws Exception {
        DataSource dataSource = dataSource();
        String schema = uniqueSchema("history_policy_case");
        createSchema(dataSource, schema);
        SchemaDefinition.ChangeSet legacy = new SchemaDefinition.ChangeSet("000-legacy",
                "applied before the policy", List.of("DROP TABLE keep_me"));
        SchemaDefinition.ChangeSet first = new SchemaDefinition.ChangeSet("001-first", "safe",
                List.of("CREATE TABLE first_t (id INTEGER)"));
        SchemaDefinition.ChangeSet second = new SchemaDefinition.ChangeSet("002-second", "safe",
                List.of("CREATE TABLE second_t (id INTEGER)"));
        SchemaDefinition.ChangeSet third = new SchemaDefinition.ChangeSet("003-third", "safe",
                List.of("CREATE TABLE third_t (id INTEGER)"));
        SchemaDefinition.ChangeSet forbidden = new SchemaDefinition.ChangeSet("004-forbidden", "destructive",
                List.of("DROP TABLE keep_me"));
        try (Connection connection = dataSource.getConnection()) {
            statement(connection, "CREATE TABLE " + schema + ".keep_me (id INTEGER)");

            // No history table: every change set is checked, and nothing runs.
            assertThatThrownBy(() -> synchronizer(dataSource, schema, false).synchronizeWithResult(connection,
                    new SchemaDefinition(Map.of(), List.of(first, legacy))))
                    .isInstanceOf(SchemaDefinitionException.class)
                    .hasMessageContaining("not allowed");
            assertThat(tableExists(connection, schema, "first_t")).isFalse();
            assertThat(tableExists(connection, schema, "schema_synchronizer_history")).isFalse();

            assertThat(synchronizer(dataSource, schema, false).synchronizeWithResult(connection,
                    new SchemaDefinition(Map.of(), List.of(first))).changeSetsApplied()).isEqualTo(1);
            statement(connection, "INSERT INTO " + schema + ".schema_synchronizer_history "
                    + "(change_id, checksum, description, execution_ms) VALUES ('000-legacy', '"
                    + ChangeSetExecutor.checksum(legacy) + "', 'applied before the policy', 0)");

            // Applied with a matching checksum: accepted and not run again, next to an unapplied safe one.
            SchemaDefinition withLegacy = new SchemaDefinition(Map.of(), List.of(legacy, first, second));
            assertThat(synchronizer(dataSource, schema, true).synchronizeWithResult(connection, withLegacy)
                    .plannedSql()).containsExactly("CREATE TABLE second_t (id INTEGER)");
            assertThat(synchronizer(dataSource, schema, false).synchronizeWithResult(connection, withLegacy)
                    .changeSetsApplied()).isEqualTo(1);
            assertThat(tableExists(connection, schema, "keep_me")).isTrue();
            assertThat(tableExists(connection, schema, "second_t")).isTrue();

            // An unapplied forbidden change set is rejected before an earlier unapplied safe one runs.
            SchemaDefinition withForbidden = new SchemaDefinition(Map.of(),
                    List.of(legacy, first, second, third, forbidden));
            for (boolean dryRun : List.of(true, false)) {
                assertThatThrownBy(() -> synchronizer(dataSource, schema, dryRun)
                        .synchronizeWithResult(connection, withForbidden))
                        .as("dryRun=%s", dryRun)
                        .isInstanceOf(SchemaDefinitionException.class)
                        .hasMessageContaining("not allowed");
            }
            assertThat(tableExists(connection, schema, "third_t")).isFalse();
            assertThat(tableExists(connection, schema, "keep_me")).isTrue();

            // An edited applied change set is a checksum error, not a policy error.
            SchemaDefinition.ChangeSet edited = new SchemaDefinition.ChangeSet("000-legacy",
                    "applied before the policy", List.of("DROP TABLE keep_me CASCADE"));
            assertThatThrownBy(() -> synchronizer(dataSource, schema, false).synchronizeWithResult(connection,
                    new SchemaDefinition(Map.of(), List.of(edited, first, second))))
                    .isInstanceOf(SchemaDefinitionException.class)
                    .hasMessageContaining("checksum mismatch");
        }
    }

    @Test
    void verificationMustReturnOneNonNullBoolean() throws Exception {
        DataSource dataSource = dataSource();
        String schema = uniqueSchema("verification_shape_case");
        createSchema(dataSource, schema);
        SchemaDefinition multipleRows = new SchemaDefinition(Map.of(), List.of(
                new SchemaDefinition.ChangeSet("multiple", "invalid verification shape",
                        List.of("SELECT 1"), "SELECT value FROM (VALUES (true), (false)) AS values(value)")));
        SchemaDefinition nullResult = new SchemaDefinition(Map.of(), List.of(
                new SchemaDefinition.ChangeSet("null-result", "invalid verification value",
                        List.of("SELECT 1"), "SELECT NULL::boolean")));
        try (Connection connection = dataSource.getConnection()) {
            assertThatThrownBy(() -> synchronizer(dataSource, schema, false)
                    .synchronizeWithResult(connection, multipleRows))
                    .isInstanceOf(SchemaDefinitionException.class)
                    .hasMessageContaining("exactly one row");
            assertThatThrownBy(() -> synchronizer(dataSource, schema, false)
                    .synchronizeWithResult(connection, nullResult))
                    .isInstanceOf(SchemaDefinitionException.class)
                    .hasMessageContaining("returned NULL");
        }
    }

    private SchemaSynchronizationResult applyAfterSignal(DataSource dataSource, SchemaSynchronizer synchronizer,
                                               SchemaDefinition definition, CountDownLatch ready,
                                               CountDownLatch start) throws Exception {
        ready.countDown();
        start.await();
        try (Connection connection = dataSource.getConnection()) {
            return synchronizer.synchronizeWithResult(connection, definition);
        }
    }

    private SchemaDefinition definition(String checkExpression) {
        return new SchemaDefinition(Map.of(), List.of(new SchemaDefinition.ChangeSet(
                "001-complex-foundation", "constraints, backfill, function, and trigger", List.of(
                "CREATE TABLE parent (id UUID PRIMARY KEY)",
                "CREATE TABLE child (id UUID PRIMARY KEY, parent_id UUID, score INTEGER)",
                "INSERT INTO parent (id) VALUES ('00000000-0000-0000-0000-000000000001')",
                "INSERT INTO child (id, parent_id) VALUES "
                        + "('00000000-0000-0000-0000-000000000002', "
                        + "'00000000-0000-0000-0000-000000000001')",
                "UPDATE child SET score = 0 WHERE score IS NULL",
                "ALTER TABLE child ALTER COLUMN score SET NOT NULL",
                "ALTER TABLE child ADD CONSTRAINT fk_child_parent FOREIGN KEY (parent_id) REFERENCES parent(id)",
                "ALTER TABLE child ADD CONSTRAINT ck_child_score " + checkExpression,
                "CREATE OR REPLACE FUNCTION reject_child_update() RETURNS trigger LANGUAGE plpgsql AS $$ "
                        + "BEGIN RAISE EXCEPTION 'immutable'; END $$",
                "CREATE TRIGGER trg_child_guard BEFORE UPDATE ON child "
                        + "FOR EACH ROW EXECUTE FUNCTION reject_child_update()"
        ), "SELECT EXISTS (SELECT 1 FROM pg_trigger WHERE tgname = 'trg_child_guard' "
                + "AND tgrelid = to_regclass('child'))")));
    }

    private DataSource dataSource() {
        PGSimpleDataSource result = new PGSimpleDataSource();
        result.setURL(System.getProperty("schema.test.jdbc.url"));
        result.setUser(System.getProperty("schema.test.jdbc.user", ""));
        result.setPassword(System.getProperty("schema.test.jdbc.password", ""));
        return result;
    }

    private String uniqueSchema(String prefix) {
        return prefix + "_" + UUID.randomUUID().toString().replace("-", "");
    }

    private long queryLong(Connection connection, String sql) throws Exception {
        try (var statement = connection.createStatement(); var rows = statement.executeQuery(sql)) {
            rows.next();
            return rows.getLong(1);
        }
    }

    private void statement(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private String queryString(Connection connection, String sql) throws Exception {
        try (var statement = connection.createStatement(); var rows = statement.executeQuery(sql)) {
            rows.next();
            return rows.getString(1);
        }
    }

    private SchemaSynchronizer synchronizer(DataSource dataSource, String schema, boolean dryRun) {
        return new SchemaSynchronizer(new ObjectMapper(), dataSource, "/schema-definition.json",
                new SchemaSynchronizerOptions(schema, "schema_synchronizer_history", 91L, dryRun, true, true));
    }

    private SchemaSynchronizer reportingSynchronizer(DataSource dataSource, String schema) {
        return new SchemaSynchronizer(new ObjectMapper(), dataSource, "/schema-definition.json",
                new SchemaSynchronizerOptions(schema, "schema_synchronizer_history", 91L, false, false, true));
    }

    private void createSchema(DataSource dataSource, String schema) throws Exception {
        try (Connection connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + schema);
        }
        cleanupSchemas.add(schema);
    }

    private boolean tableExists(Connection connection, String schema, String table) throws Exception {
        try (var rows = connection.getMetaData().getTables(null, schema, table, new String[]{"TABLE"})) {
            return rows.next();
        }
    }
}
