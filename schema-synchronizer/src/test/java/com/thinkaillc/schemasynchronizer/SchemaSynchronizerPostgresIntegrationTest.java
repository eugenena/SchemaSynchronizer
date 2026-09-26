// Copyright 2026 ThinkAI LLC
// SPDX-License-Identifier: Apache-2.0

package com.thinkaillc.schemasynchronizer;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
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

@EnabledIfSystemProperty(named = "schema.test.jdbc.url", matches = ".+")
class SchemaSynchronizerPostgresIntegrationTest {
    private final List<String> cleanupSchemas = new ArrayList<>();

    @TempDir
    Path tempDirectory;

    @AfterEach
    void removeTestSchemas() throws Exception {
        DataSource dataSource = dataSource();
        try (Connection connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            for (String schema : cleanupSchemas) {
                statement.execute("DROP SCHEMA " + SqlIdentifiers.requireIdentifier(schema, "test schema")
                        + " CASCADE");
            }
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
                    .isInstanceOf(IllegalStateException.class)
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
                    + ".accounts (id BIGINT PRIMARY KEY, email VARCHAR(320) NOT NULL UNIQUE, display_name TEXT)");
            statement.execute("CREATE INDEX idx_accounts_display_name ON "
                    + sourceSchema + ".accounts (display_name)");
        }

        Path definitionPath = tempDirectory.resolve("schema-definition.json");
        SchemaSnapshotWriter.main(new String[]{
                System.getProperty("schema.test.jdbc.url"),
                System.getProperty("schema.test.jdbc.user", ""),
                System.getProperty("schema.test.jdbc.password", ""),
                sourceSchema,
                definitionPath.toString()
        });
        SchemaDefinition definition = new ObjectMapper().readValue(definitionPath.toFile(), SchemaDefinition.class);

        assertThat(definition.tables().get("accounts").indexes())
                .containsExactly(
                        "CREATE UNIQUE INDEX IF NOT EXISTS accounts_email_key ON accounts (email)",
                        "CREATE INDEX IF NOT EXISTS idx_accounts_display_name ON accounts (display_name)");
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
        assertThatThrownBy(() -> SchemaSnapshotWriter.main(new String[]{
                System.getProperty("schema.test.jdbc.url"),
                System.getProperty("schema.test.jdbc.user", ""),
                System.getProperty("schema.test.jdbc.password", ""),
                sourceSchema,
                definitionPath.toString()
        }))
                .isInstanceOf(IllegalStateException.class)
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
                    .isInstanceOf(IllegalArgumentException.class)
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
            for (String sql : definition.changes().getFirst().statements()) {
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

        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> applyAfterSignal(dataSource, synchronizer, definition, ready, start));
            var second = executor.submit(() -> applyAfterSignal(dataSource, synchronizer, definition, ready, start));
            ready.await();
            start.countDown();
            assertThat(first.get().changeSetsApplied() + second.get().changeSetsApplied()).isEqualTo(1);
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

        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> applyAfterSignal(dataSource, synchronizer, definition, ready, start));
            var second = executor.submit(() -> applyAfterSignal(dataSource, synchronizer, definition, ready, start));
            ready.await();
            start.countDown();
            assertThat(first.get().tablesCreated() + second.get().tablesCreated()).isEqualTo(1);
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
                    .isInstanceOf(IllegalStateException.class)
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
                    .isInstanceOf(IllegalArgumentException.class);
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
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("primary key is missing");
            SchemaSynchronizationResult missingPrimaryKey = reportingSynchronizer(dataSource, schema)
                    .synchronizeWithResult(connection, definition);
            assertThat(missingPrimaryKey.pendingSql()).contains(
                    "ALTER TABLE items ADD PRIMARY KEY (id); -- pending: primary key is missing");
            try (var statement = connection.createStatement()) {
                statement.execute("SET search_path TO " + schema);
                statement.execute("ALTER TABLE items ADD PRIMARY KEY (code)");
            }
            SchemaSynchronizationResult driftedPrimaryKey = reportingSynchronizer(dataSource, schema)
                    .synchronizeWithResult(connection, definition);
            assertThat(driftedPrimaryKey.pendingSql())
                    .anySatisfy(sql -> assertThat(sql)
                            .matches("ALTER TABLE items DROP CONSTRAINT items_pkey\\d*;"))
                    .contains("ALTER TABLE items ADD PRIMARY KEY (id);",
                            "ALTER TABLE items ALTER COLUMN code DROP NOT NULL;");
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
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("index definition drift");
            SchemaSynchronizationResult driftedIndex = reportingSynchronizer(dataSource, schema)
                    .synchronizeWithResult(connection, definition);
            assertThat(driftedIndex.pendingSql()).containsSubsequence(
                    "DROP INDEX IF EXISTS idx_items_code;",
                    "CREATE INDEX IF NOT EXISTS idx_items_code ON items (code);");
            try (var statement = connection.createStatement()) {
                statement.execute("SET search_path TO " + schema);
                statement.execute("DROP INDEX idx_items_code");
                statement.execute("CREATE INDEX idx_items_code ON items (code)");
                statement.execute("CREATE TABLE forgotten_table (value INTEGER)");
            }
            assertThatThrownBy(() -> synchronizer.synchronizeWithResult(connection, definition))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("table absent from definition");
            SchemaSynchronizationResult orphanTable = reportingSynchronizer(dataSource, schema)
                    .synchronizeWithResult(connection, definition);
            assertThat(orphanTable.pendingSql()).contains(
                    "DROP TABLE forgotten_table; -- pending: table absent from definition");
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
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("index definition drift");
            SchemaSynchronizationResult plan = reportingSynchronizer(dataSource, schema)
                    .synchronizeWithResult(connection, definition);
            assertThat(plan.pendingSql()).containsSubsequence(
                    "DROP INDEX IF EXISTS uq_jobs_active;",
                    "CREATE UNIQUE INDEX IF NOT EXISTS uq_jobs_active ON jobs (id) WHERE status = 'ACTIVE';");
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
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("exactly one row");
            assertThatThrownBy(() -> synchronizer(dataSource, schema, false)
                    .synchronizeWithResult(connection, nullResult))
                    .isInstanceOf(IllegalArgumentException.class)
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
