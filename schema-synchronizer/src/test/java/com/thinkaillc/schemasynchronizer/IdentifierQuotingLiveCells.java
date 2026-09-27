// Copyright 2026 ThinkAI LLC
// SPDX-License-Identifier: Apache-2.0

package com.thinkaillc.schemasynchronizer;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Live identifier-quoting cells shared by every engine. Each subclass binds two private, per-run
 * namespaces (a working one and an empty replay target) and drops them afterwards.
 */
abstract class IdentifierQuotingLiveCells {

    /** Tables named by reserved words; every one also has reserved-word columns, key, and indexes. */
    private static final List<String> TABLES = List.of("order", "user", "group");

    abstract DatabaseDialect dialect();

    /** A connection bound to the working namespace (the synchronizer's preconditions hold). */
    abstract Connection connection() throws Exception;

    /** A connection bound to the empty replay namespace. */
    abstract Connection replayConnection() throws Exception;

    /** The configured schema option for {@link #connection()}. */
    abstract String schema();

    /** The configured schema option for {@link #replayConnection()}. */
    abstract String replaySchema();

    /** Whether the server compares table names case-sensitively in the working namespace. */
    abstract boolean tablesCaseSensitive(Connection connection) throws Exception;

    /** Whether the server compares column names case-sensitively in the working namespace. */
    abstract boolean columnsCaseSensitive(Connection connection) throws Exception;

    String bigint() {
        return dialect() == DatabaseDialect.ORACLE ? "NUMBER(19)" : "BIGINT";
    }

    String varchar() {
        return dialect() == DatabaseDialect.ORACLE ? "VARCHAR2(50)" : "VARCHAR(50)";
    }

    /** Raw DDL table reference with the exact (case-preserved) name, for fixtures the declaration cannot make. */
    String rawTable(String exactName) {
        return SqlIdentifiers.tableReference(dialect(), schema(), exactName);
    }

    SchemaSynchronizer synchronizer(String schema) {
        return new SchemaSynchronizer(new ObjectMapper(), null, "",
                new SchemaSynchronizerOptions(schema, "schema_synchronizer_history", 7_249_031_147L,
                        false, true, true));
    }

    private String q(String declared) {
        return SqlIdentifiers.quote(dialect(), declared);
    }

    private SchemaDefinition.TableDef reservedTable(String table, boolean withEach) {
        String ifNotExists = dialect().supportsCreateIndexIfNotExists() ? "IF NOT EXISTS " : "";
        String createSql = "CREATE TABLE " + q(table) + " (" + q(table) + " " + bigint() + " NOT NULL, "
                + q("desc") + " " + varchar() + ", " + q("default") + " " + varchar() + ", "
                + q("key") + " " + bigint() + ", PRIMARY KEY (" + q(table) + "))";
        List<SchemaDefinition.ColumnDef> columns = new ArrayList<>(List.of(
                new SchemaDefinition.ColumnDef(table, bigint() + " NOT NULL"),
                new SchemaDefinition.ColumnDef("desc", varchar()),
                new SchemaDefinition.ColumnDef("default", varchar()),
                new SchemaDefinition.ColumnDef("key", bigint())));
        if (withEach) {
            columns.add(new SchemaDefinition.ColumnDef("each", varchar()));
        }
        List<String> indexes = List.of(
                "CREATE INDEX " + ifNotExists + q("idx_" + table + "_desc") + " ON " + q(table) + " (" + q("desc") + ")",
                "CREATE UNIQUE INDEX " + ifNotExists + q("uq_" + table + "_key") + " ON " + q(table) + " ("
                        + q("key") + ", " + q("default") + ")");
        return new SchemaDefinition.TableDef(createSql, columns, indexes);
    }

    private SchemaDefinition reservedDefinition(boolean withEach) {
        Map<String, SchemaDefinition.TableDef> tables = new LinkedHashMap<>();
        for (String table : TABLES) {
            tables.put(table, reservedTable(table, withEach && table.equals("order")));
        }
        return new SchemaDefinition(2, dialect().id(), tables, List.of());
    }

    /** Exact names of the base tables in the connection's namespace. */
    private List<String> liveTables(Connection connection, String schema) throws Exception {
        var meta = connection.getMetaData();
        String schemaPattern = dialect().metadataSchemaPattern(schema);
        List<String> tables = new ArrayList<>();
        try (ResultSet rows = meta.getTables(dialect().metadataCatalog(connection, schema), schemaPattern, "%",
                new String[]{"TABLE"})) {
            while (rows.next()) {
                if (DatabaseDialect.isRequestedObject(rows, schemaPattern, null)) {
                    tables.add(rows.getString("TABLE_NAME"));
                }
            }
        }
        return tables;
    }

    private List<String> liveColumns(Connection connection, String schema, String exactTable) throws Exception {
        var meta = connection.getMetaData();
        String schemaPattern = dialect().metadataSchemaPattern(schema);
        List<String> columns = new ArrayList<>();
        try (ResultSet rows = meta.getColumns(dialect().metadataCatalog(connection, schema), schemaPattern,
                exactTable, "%")) {
            while (rows.next()) {
                if (DatabaseDialect.isRequestedObject(rows, schemaPattern, exactTable)) {
                    columns.add(rows.getString("COLUMN_NAME"));
                }
            }
        }
        return columns;
    }

    private String stored(String declared) {
        return SqlIdentifiers.storedForm(dialect(), declared);
    }

    @Test
    void reservedWordsCreateResyncAddAColumnAndReplayASnapshot(@TempDir Path tempDir) throws Exception {
        SchemaDefinition initial = reservedDefinition(false);
        try (Connection connection = connection()) {
            SchemaSynchronizationResult created = synchronizer(schema()).synchronizeWithResult(connection, initial);
            assertThat(created.tablesCreated()).isEqualTo(TABLES.size());
            assertThat(created.pendingSql()).isEmpty();
            assertThat(created.lockReleased()).isTrue();
            assertThat(created.cleanupWarnings()).isEmpty();
        }
        try (Connection connection = connection()) {
            assertThat(liveTables(connection, schema())).contains(stored("order"), stored("user"), stored("group"));
            assertThat(liveColumns(connection, schema(), stored("order")))
                    .contains(stored("order"), stored("desc"), stored("default"), stored("key"));
            SchemaSynchronizationResult again = synchronizer(schema()).synchronizeWithResult(connection, initial);
            assertThat(again.changed()).as("re-sync of an unchanged definition").isFalse();
            assertThat(again.pendingSql()).isEmpty();
        }

        SchemaDefinition withEach = reservedDefinition(true);
        try (Connection connection = connection()) {
            SchemaSynchronizationResult added = synchronizer(schema()).synchronizeWithResult(connection, withEach);
            assertThat(added.columnsAdded()).isEqualTo(1);
            assertThat(added.pendingSql()).isEmpty();
        }
        try (Connection connection = connection()) {
            assertThat(liveColumns(connection, schema(), stored("order"))).contains(stored("each"));
            SchemaSynchronizationResult again = synchronizer(schema()).synchronizeWithResult(connection, withEach);
            assertThat(again.changed()).isFalse();
            assertThat(again.pendingSql()).isEmpty();
        }

        Path snapshotFile = tempDir.resolve("snapshot.json");
        try (Connection connection = connection()) {
            SchemaSnapshotWriter.writeSnapshot(connection, schema(), snapshotFile);
        }
        String snapshotJson = java.nio.file.Files.readString(snapshotFile);
        assertThat(snapshotJson).as("snapshot SQL quotes every identifier").contains(
                q("order").replace("\"", "\\\""), q("desc").replace("\"", "\\\""));
        SchemaDefinition snapshot = new ObjectMapper().readValue(snapshotFile.toFile(), SchemaDefinition.class);
        assertThat(snapshot.tables()).containsOnlyKeys("order", "user", "group");

        try (Connection connection = connection()) {
            SchemaSynchronizationResult replayed = synchronizer(schema()).synchronizeWithResult(connection, snapshot);
            assertThat(replayed.changed()).as("snapshot replayed onto its source").isFalse();
            assertThat(replayed.pendingSql()).isEmpty();
        }
        try (Connection replay = replayConnection()) {
            SchemaSynchronizationResult fresh = synchronizer(replaySchema()).synchronizeWithResult(replay, snapshot);
            assertThat(fresh.tablesCreated()).isEqualTo(TABLES.size());
            assertThat(fresh.pendingSql()).isEmpty();
        }
        try (Connection replay = replayConnection()) {
            SchemaSynchronizationResult again = synchronizer(replaySchema()).synchronizeWithResult(replay, snapshot);
            assertThat(again.changed()).isFalse();
            assertThat(again.pendingSql()).isEmpty();
            assertThat(liveColumns(replay, replaySchema(), stored("order"))).contains(stored("each"));
        }
    }

    SchemaSynchronizer dryRunSynchronizer(String schema) {
        return new SchemaSynchronizer(new ObjectMapper(), null, "",
                new SchemaSynchronizerOptions(schema, "schema_synchronizer_history", 7_249_031_147L,
                        true, true, true));
    }

    @Test
    void dryRunTreatsATableItWouldCreateAsCreatedWithItsDeclaredColumnsAndKey() throws Exception {
        try (Connection connection = connection()) {
            SchemaSynchronizationResult firstDeploy = dryRunSynchronizer(schema())
                    .synchronizeWithResult(connection, reservedDefinition(true));
            assertThat(firstDeploy.dryRun()).isTrue();
            assertThat(firstDeploy.pendingSql()).as("first-deploy dry run").isEmpty();
            assertThat(firstDeploy.tablesCreated()).isEqualTo(TABLES.size());
            assertThat(firstDeploy.columnsAdded()).as("each is declared but absent from createSql").isEqualTo(1);
            assertThat(firstDeploy.plannedSql()).filteredOn(sql -> sql.toUpperCase(java.util.Locale.ROOT)
                    .contains(" ADD ")).singleElement().asString().contains(q("each"));
            assertThat(firstDeploy.plannedSql()).filteredOn(sql -> sql.toUpperCase(java.util.Locale.ROOT)
                    .contains("INDEX")).hasSize(2 * TABLES.size());
            assertThat(liveTables(connection, schema())).as("dry run created nothing").isEmpty();
        }

        // An existing table is still compared against the live catalog in the same dry run.
        SchemaDefinition initial = reservedDefinition(false);
        Map<String, SchemaDefinition.TableDef> onlyOrder = new LinkedHashMap<>();
        onlyOrder.put("order", initial.tables().get("order"));
        try (Connection connection = connection()) {
            synchronizer(schema()).synchronizeWithResult(connection,
                    new SchemaDefinition(2, dialect().id(), onlyOrder, List.of()));
        }
        try (Connection connection = connection()) {
            SchemaSynchronizationResult mixed = dryRunSynchronizer(schema())
                    .synchronizeWithResult(connection, reservedDefinition(true));
            assertThat(mixed.pendingSql()).isEmpty();
            assertThat(mixed.tablesCreated()).isEqualTo(TABLES.size() - 1);
            assertThat(mixed.columnsAdded()).as("planned for the existing table only").isEqualTo(1);
            assertThat(mixed.plannedSql()).filteredOn(sql -> sql.toUpperCase(java.util.Locale.ROOT)
                    .contains(" ADD ")).singleElement().asString().contains(q("each"));
            assertThat(liveTables(connection, schema())).contains(stored("order"))
                    .doesNotContain(stored("user"), stored("group"));
            assertThat(liveColumns(connection, schema(), stored("order"))).doesNotContain(stored("each"));
        }
    }

    @Test
    void aFirstDeployDryRunReportsWhatTheRealRunDoesWhenCreateSqlAndColumnsDisagree() throws Exception {
        SchemaDefinition disagreeing = new SchemaDefinition(2, dialect().id(), Map.of("items",
                new SchemaDefinition.TableDef("CREATE TABLE " + q("items") + " (" + q("id") + " " + bigint()
                        + " NOT NULL, " + q("desc") + " " + varchar() + ", PRIMARY KEY (" + q("id") + "))",
                        List.of(new SchemaDefinition.ColumnDef("id", bigint() + " NOT NULL"),
                                new SchemaDefinition.ColumnDef("key", bigint())), List.of())), List.of());
        SchemaSynchronizationResult dryRun;
        try (Connection connection = connection()) {
            dryRun = new SchemaSynchronizer(new ObjectMapper(), null, "",
                    new SchemaSynchronizerOptions(schema(), "schema_synchronizer_history", 7_249_031_147L,
                            true, false, true)).synchronizeWithResult(connection, disagreeing);
            assertThat(liveTables(connection, schema())).doesNotContain(stored("items"));
        }
        SchemaSynchronizationResult real;
        try (Connection connection = connection()) {
            real = new SchemaSynchronizer(new ObjectMapper(), null, "",
                    new SchemaSynchronizerOptions(schema(), "schema_synchronizer_history", 7_249_031_147L,
                            false, false, true)).synchronizeWithResult(connection, disagreeing);
        }
        assertThat(real.columnsAdded()).isEqualTo(1);
        assertThat(real.pendingSql()).singleElement().asString().contains("DROP COLUMN " + q("desc"));
        assertThat(dryRun.pendingSql()).as("dry run pending = real run pending").isEqualTo(real.pendingSql());
        assertThat(dryRun.columnsAdded()).isEqualTo(real.columnsAdded());
        assertThat(dryRun.plannedSql()).anyMatch(sql -> sql.contains(q("key")));
    }

    @Test
    void aChangeSetCreatingAViewIsRejectedAsNotYetSupportedBeforeAnythingRuns() throws Exception {
        String from = dialect() == DatabaseDialect.ORACLE ? " FROM dual" : "";
        SchemaDefinition withView = new SchemaDefinition(2, dialect().id(), Map.of(), List.of(
                new SchemaDefinition.ChangeSet("001-view", "a view",
                        List.of("CREATE VIEW items_v AS SELECT 1 AS x" + from))));
        try (Connection connection = connection()) {
            List<String> before = liveTables(connection, schema());
            assertThatThrownBy(() -> synchronizer(schema()).synchronizeWithResult(connection, withView))
                    .isInstanceOf(SchemaDefinitionException.class)
                    .hasMessageContaining("CREATE VIEW is not yet supported in change sets")
                    .hasMessageNotContaining("exactly one statement");
            assertThat(liveTables(connection, schema())).containsExactlyInAnyOrderElementsOf(before);
        }
    }

    @Test
    void tableThatDiffersOnlyByCaseConflictsExactlyWhereTheServerComparesCaseSensitively() throws Exception {
        try (Connection connection = connection(); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE " + rawTable("Orders") + " (id " + bigint() + " NOT NULL PRIMARY KEY)");
        }
        SchemaDefinition declared = new SchemaDefinition(2, dialect().id(), Map.of("orders",
                new SchemaDefinition.TableDef("CREATE TABLE orders (id " + bigint() + " NOT NULL, PRIMARY KEY (id))",
                        List.of(new SchemaDefinition.ColumnDef("id", bigint() + " NOT NULL")), List.of())),
                List.of());
        try (Connection connection = connection()) {
            boolean sensitive = tablesCaseSensitive(connection);
            List<String> before = liveTables(connection, schema());
            if (sensitive) {
                assertThatThrownBy(() -> synchronizer(schema()).synchronizeWithResult(connection, declared))
                        .isInstanceOf(SchemaDefinitionException.class)
                        .hasMessageContaining(SqlIdentifiers.quoteExact(dialect(), "Orders"))
                        .hasMessageContaining(q("orders"));
                assertThat(liveTables(connection, schema())).as("nothing created on conflict")
                        .containsExactlyInAnyOrderElementsOf(before);
            } else {
                SchemaSynchronizationResult result = synchronizer(schema()).synchronizeWithResult(connection, declared);
                assertThat(result.tablesCreated()).isZero();
                assertThat(result.pendingSql()).isEmpty();
            }
        }
    }

    @Test
    void columnThatDiffersOnlyByCaseResolvesExactlyWhereTheServerComparesInsensitively() throws Exception {
        try (Connection connection = connection(); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE " + rawTable(stored("items")) + " (id " + bigint()
                    + " NOT NULL PRIMARY KEY, " + SqlIdentifiers.quoteExact(dialect(), "Label") + " " + varchar() + ")");
        }
        SchemaDefinition declared = new SchemaDefinition(2, dialect().id(), Map.of("items",
                new SchemaDefinition.TableDef("CREATE TABLE items (id " + bigint() + " NOT NULL, label " + varchar()
                        + ", PRIMARY KEY (id))",
                        List.of(new SchemaDefinition.ColumnDef("id", bigint() + " NOT NULL"),
                                new SchemaDefinition.ColumnDef("label", varchar())), List.of())),
                List.of());
        try (Connection connection = connection()) {
            if (columnsCaseSensitive(connection)) {
                assertThatThrownBy(() -> synchronizer(schema()).synchronizeWithResult(connection, declared))
                        .isInstanceOf(SchemaDefinitionException.class)
                        .hasMessageContaining(SqlIdentifiers.quoteExact(dialect(), "Label"))
                        .hasMessageContaining(q("label"));
                assertThat(liveColumns(connection, schema(), stored("items"))).containsExactlyInAnyOrder(
                        stored("id"), "Label");
            } else {
                SchemaSynchronizationResult result = synchronizer(schema()).synchronizeWithResult(connection, declared);
                assertThat(result.columnsAdded()).isZero();
                assertThat(result.changed()).isFalse();
                assertThat(result.pendingSql()).isEmpty();
            }
        }
    }

    @Test
    void snapshotRefusesNamesThatCannotRoundTrip(@TempDir Path tempDir) throws Exception {
        try (Connection connection = connection(); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE " + rawTable("my items") + " (id " + bigint() + " NOT NULL PRIMARY KEY)");
        }
        try (Connection connection = connection()) {
            assertThatThrownBy(() -> SchemaSnapshotWriter.writeSnapshot(connection, schema(),
                    tempDir.resolve("snapshot.json")))
                    .isInstanceOf(SchemaSynchronizationException.class)
                    .hasMessageContaining(SqlIdentifiers.quoteExact(dialect(), "my items"));
        }
    }
}
