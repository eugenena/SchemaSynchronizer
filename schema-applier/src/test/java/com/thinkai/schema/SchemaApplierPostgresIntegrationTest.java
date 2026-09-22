package com.thinkai.schema;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.postgresql.ds.PGSimpleDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@EnabledIfSystemProperty(named = "schema.test.jdbc.url", matches = ".+")
class SchemaApplierPostgresIntegrationTest {
    private final List<String> cleanupSchemas = new ArrayList<>();

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
        SchemaApplier applier = applier(dataSource, schema, false);

        try (Connection connection = dataSource.getConnection()) {
            SchemaApplyResult first = applier.applySchemaWithResult(connection, definition);
            assertThat(first.changeSetsApplied()).isEqualTo(1);
        }
        try (Connection connection = dataSource.getConnection()) {
            SchemaApplyResult replay = applier.applySchemaWithResult(connection, definition);
            assertThat(replay.changeSetsApplied()).isZero();
            assertThat(queryLong(connection, "SELECT count(*) FROM " + schema
                    + ".thinkai_schema_history")).isEqualTo(1);
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
            assertThatThrownBy(() -> applier.applySchemaWithResult(connection, edited))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("checksum mismatch");
        }
    }

    @Test
    void dryRunAndDestructivePreflightDoNotMutateDatabase() throws Exception {
        DataSource dataSource = dataSource();
        String schema = uniqueSchema("dry_run_case");
        createSchema(dataSource, schema);
        SchemaApplier dryRun = applier(dataSource, schema, true);
        try (Connection connection = dataSource.getConnection()) {
            SchemaApplyResult result = dryRun.applySchemaWithResult(connection, definition("CHECK (score >= 0)"));
            assertThat(result.plannedSql()).isNotEmpty();
            assertThat(tableExists(connection, schema, "child")).isFalse();
            assertThat(tableExists(connection, schema, "thinkai_schema_history")).isFalse();
        }

        SchemaDefinition destructive = new SchemaDefinition(Map.of(), List.of(
                new SchemaDefinition.ChangeSet("bad", "must fail", List.of("DROP TABLE child"))));
        try (Connection connection = dataSource.getConnection()) {
            assertThatThrownBy(() -> applier(dataSource, schema, false)
                    .applySchemaWithResult(connection, destructive))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("not allowed");
            assertThat(tableExists(connection, schema, "thinkai_schema_history")).isFalse();
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
            SchemaApplyResult adopted = applier(dataSource, schema, false)
                    .applySchemaWithResult(connection, definition);
            assertThat(adopted.changeSetsApplied()).isEqualTo(1);
            assertThat(queryLong(connection, "SELECT count(*) FROM " + schema
                    + ".thinkai_schema_history")).isEqualTo(1);
        }
    }

    @Test
    void advisoryLockSerializesConcurrentStartup() throws Exception {
        DataSource dataSource = dataSource();
        String schema = uniqueSchema("concurrent_case");
        createSchema(dataSource, schema);
        SchemaDefinition definition = definition("CHECK (score >= 0)");
        SchemaApplier applier = applier(dataSource, schema, false);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> applyAfterSignal(dataSource, applier, definition, ready, start));
            var second = executor.submit(() -> applyAfterSignal(dataSource, applier, definition, ready, start));
            ready.await();
            start.countDown();
            assertThat(first.get().changeSetsApplied() + second.get().changeSetsApplied()).isEqualTo(1);
        }
        try (Connection connection = dataSource.getConnection()) {
            assertThat(queryLong(connection, "SELECT count(*) FROM " + schema
                    + ".thinkai_schema_history")).isEqualTo(1);
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
        SchemaApplier applier = applier(dataSource, schema, false);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> applyAfterSignal(dataSource, applier, definition, ready, start));
            var second = executor.submit(() -> applyAfterSignal(dataSource, applier, definition, ready, start));
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
        SchemaApplier applier = applier(dataSource, schema, false);
        try (Connection connection = dataSource.getConnection()) {
            applier.applySchemaWithResult(connection, definition("CHECK (score >= 0)"));
        }
        try (Connection connection = dataSource.getConnection()) {
            assertThatThrownBy(() -> applier.applySchemaWithResult(
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
        SchemaApplier applier = applier(dataSource, schema, false);
        try (Connection connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            statement.execute("SET search_path TO " + schema);
            statement.execute("CREATE TABLE caller_work (value INTEGER)");
            statement.execute("INSERT INTO caller_work VALUES (1)");
            applier.applySchemaWithResult(connection, new SchemaDefinition(Map.of(
                    "managed", new SchemaDefinition.TableDef("CREATE TABLE managed (id BIGINT PRIMARY KEY)",
                    List.of(new SchemaDefinition.ColumnDef("id", "BIGINT NOT NULL")),
                    List.of("CREATE UNIQUE INDEX IF NOT EXISTS managed_pkey ON managed (id)")))));
            assertThat(connection.getAutoCommit()).isFalse();
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
            assertThatThrownBy(() -> applier.applySchemaWithResult(connection,
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
            SchemaApplyResult result = applier(dataSource, schema, false)
                    .applySchemaWithResult(connection, definition);
            assertThat(result.tablesCreated()).isEqualTo(2);
            assertThat(result.changeSetsApplied()).isEqualTo(1);
            assertThat(queryLong(connection, "SELECT count(*) FROM pg_constraint c JOIN pg_namespace n "
                    + "ON n.oid = c.connamespace WHERE n.nspname = '" + schema + "' "
                    + "AND conname = 'fk_child_parent'")).isEqualTo(1);
        }
    }

    private SchemaApplyResult applyAfterSignal(DataSource dataSource, SchemaApplier applier,
                                               SchemaDefinition definition, CountDownLatch ready,
                                               CountDownLatch start) throws Exception {
        ready.countDown();
        start.await();
        try (Connection connection = dataSource.getConnection()) {
            return applier.applySchemaWithResult(connection, definition);
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

    private SchemaApplier applier(DataSource dataSource, String schema, boolean dryRun) {
        return new SchemaApplier(new ObjectMapper(), dataSource, "/schema-definition.json",
                new SchemaApplierOptions(schema, "thinkai_schema_history", 91L, dryRun, true, true));
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
